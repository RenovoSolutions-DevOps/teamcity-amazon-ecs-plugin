package jetbrains.buildServer.clouds.ecs

import com.amazonaws.SdkClientException
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.auth.BasicSessionCredentials
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.SecurityContextEx
import jetbrains.buildServer.serverSide.auth.AccessDeniedException
import jetbrains.buildServer.serverSide.connections.credentials.ConnectionCredentialsException
import jetbrains.buildServer.serverSide.connections.credentials.ProjectConnectionCredentialsManager
import jetbrains.buildServer.util.amazon.AWSCommonParams

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
 * AWS SDK v1 credentials provider backed by a TeamCity AWS connection.
 *
 * Credentials are requested from [ProjectConnectionCredentialsManager] on every call. The server caches session
 * credentials itself and knows when they really expire (the properties it returns carry no expiry time), so this
 * class deliberately keeps no cache of its own: a reused copy could outlive the server's refresh. The SDK asks for
 * credentials once per API request, which is cheap against that cache.
 *
 * The connection must be visible to the project that owns the cloud profile (defined in it, or in a parent project
 * with "Available for sub-projects" enabled).
 */
class AwsConnectionCredentialsProvider(private val credentialsManager: ProjectConnectionCredentialsManager,
                                       private val projectManager: ProjectManager,
                                       private val projectId: String,
                                       private val connectionId: String) : AWSCredentialsProvider {
    override fun getCredentials(): AWSCredentials {
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

    override fun refresh() {
        // nothing to refresh: every getCredentials() call asks the connection again
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
                                    private val projectManager: ProjectManager,
                                    private val securityContext: SecurityContextEx) {
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

    /**
     * Same as [create], for requests made by a user (profile form, test connection, choosers). Using an AWS connection
     * hands out the server's AWS credentials, so the user must be allowed to edit the project the profile belongs to.
     * Profiles that carry their own credentials need no such check.
     * @throws AccessDeniedException if the user cannot edit the project
     */
    fun createForUser(properties: Map<String, String>, projectId: String?): AWSCredentialsProvider {
        if (!properties[AWS_CONNECTION_ID_PARAM].isNullOrBlank()) {
            checkCanEditProject(projectId)
        }
        return create(properties, projectId)
    }

    /** True if the current user may edit the project, so it may list and pick the connections shared with it. */
    fun canEditProject(projectId: String?): Boolean = try {
        checkCanEditProject(projectId)
        true
    } catch (e: AccessDeniedException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    private fun checkCanEditProject(projectId: String?) {
        val project = projectId?.let { projectManager.findProject(it) }
                ?: throw IllegalArgumentException("Unknown project '$projectId'")
        securityContext.accessChecker.checkCanEditProject(project)
    }
}
