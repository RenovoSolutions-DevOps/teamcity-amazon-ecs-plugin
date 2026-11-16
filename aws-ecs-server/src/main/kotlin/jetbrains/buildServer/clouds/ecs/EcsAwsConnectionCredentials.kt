package jetbrains.buildServer.clouds.ecs

import com.amazonaws.SdkClientException
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.auth.BasicSessionCredentials
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.connections.credentials.ConnectionCredentialsException
import jetbrains.buildServer.serverSide.connections.credentials.ProjectConnectionCredentialsManager
import jetbrains.buildServer.util.amazon.AWSCommonParams
import java.util.concurrent.TimeUnit

/**
 * Name of the cloud profile property that holds the id of the TeamCity "AWS" connection to use.
 * Same name the AWS Core plugin uses (AwsCloudConnectorConstants.CHOSEN_AWS_CONN_ID_PARAM).
 */
const val AWS_CONNECTION_ID_PARAM = "awsConnectionId"

/** Provider type of TeamCity AWS connections (AwsCloudConnectorConstants.TYPE). */
const val AWS_CONNECTION_TYPE = "AWS"

// Keys of the credential properties returned by AWS connections (AwsConnectionCredentialsConstants).
private const val ACCESS_KEY_ID_KEY = "awsAccessKeyId"
private const val SECRET_ACCESS_KEY_KEY = "awsSecretAccessKey"
private const val SESSION_TOKEN_KEY = "awsSessionToken"

/**
 * How long credentials obtained from a connection are reused before the connection is asked again.
 * AWS connections of the IAM role type hand out STS session credentials that last at least 15 minutes,
 * so a few minutes of reuse is safe and avoids an STS call on every ECS API request.
 */
private val CREDENTIALS_REUSE_NANOS = TimeUnit.MINUTES.toNanos(5)

/**
 * AWS SDK v1 credentials provider backed by a TeamCity AWS connection.
 *
 * Credentials are requested from [ProjectConnectionCredentialsManager] on demand and refreshed, so a profile
 * keeps working after the first set of session credentials expires. The connection must be visible to the project
 * that owns the cloud profile (defined in it, or in a parent project with "Available for sub-projects" enabled).
 */
class AwsConnectionCredentialsProvider(private val credentialsManager: ProjectConnectionCredentialsManager,
                                       private val projectManager: ProjectManager,
                                       private val projectId: String,
                                       private val connectionId: String,
                                       private val nanoTime: () -> Long = System::nanoTime) : AWSCredentialsProvider {
    private class Cached(val credentials: AWSCredentials, val obtainedAtNanos: Long)

    @Volatile
    private var cached: Cached? = null

    override fun getCredentials(): AWSCredentials {
        val now = nanoTime()
        cached?.let { if (now - it.obtainedAtNanos < CREDENTIALS_REUSE_NANOS) return it.credentials }
        synchronized(this) {
            cached?.let { if (nanoTime() - it.obtainedAtNanos < CREDENTIALS_REUSE_NANOS) return it.credentials }
            val fresh = request()
            cached = Cached(fresh, nanoTime())
            return fresh
        }
    }

    override fun refresh() {
        cached = null
    }

    private fun request(): AWSCredentials {
        val project = projectManager.findProject(projectId)
                ?: throw SdkClientException("Cannot find project '$projectId' to resolve AWS connection '$connectionId'")
        val properties = try {
            credentialsManager.requestConnectionCredentials(project, connectionId).properties
        } catch (e: ConnectionCredentialsException) {
            throw SdkClientException("Cannot get credentials of AWS connection '$connectionId' for project '$projectId': ${e.message}", e)
        }
        val accessKeyId = properties[ACCESS_KEY_ID_KEY]
        val secretAccessKey = properties[SECRET_ACCESS_KEY_KEY]
        if (accessKeyId.isNullOrEmpty() || secretAccessKey.isNullOrEmpty()) {
            throw SdkClientException("AWS connection '$connectionId' returned no access key or secret key")
        }
        val sessionToken = properties[SESSION_TOKEN_KEY]
        return if (sessionToken.isNullOrEmpty()) {
            BasicAWSCredentials(accessKeyId, secretAccessKey)
        } else {
            BasicSessionCredentials(accessKeyId, secretAccessKey, sessionToken)
        }
    }
}

/**
 * Finds a project by its internal id (what cloud client state carries) or, failing that, by its external id
 * (what the admin pages pass as `projectId`).
 */
fun ProjectManager.findProject(id: String): SProject? = findProjectById(id) ?: findProjectByExternalId(id)

/**
 * Chooses how a cloud profile authenticates to AWS: through an AWS connection when the profile has one selected,
 * otherwise through the credential parameters of the profile itself, as before.
 */
class EcsCredentialsProviderFactory(private val credentialsManager: ProjectConnectionCredentialsManager,
                                    private val projectManager: ProjectManager) {
    fun create(properties: Map<String, String>, projectId: String?): AWSCredentialsProvider {
        val connectionId = properties[AWS_CONNECTION_ID_PARAM]?.trim()
        if (connectionId.isNullOrEmpty()) {
            return AWSCommonParams.getCredentialsProvider(properties)
        }
        if (projectId.isNullOrEmpty()) {
            throw IllegalArgumentException("Cannot use AWS connection '$connectionId': the project of the cloud profile is unknown")
        }
        return AwsConnectionCredentialsProvider(credentialsManager, projectManager, projectId, connectionId)
    }
}
