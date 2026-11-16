package jetbrains.buildServer.clouds.ecs

import com.amazonaws.SdkClientException
import com.amazonaws.auth.AWSSessionCredentials
import jetbrains.buildServer.serverSide.ProjectManager
import jetbrains.buildServer.serverSide.SProject
import jetbrains.buildServer.serverSide.connections.credentials.ConnectionCredentials
import jetbrains.buildServer.serverSide.connections.credentials.ConnectionCredentialsException
import jetbrains.buildServer.serverSide.connections.credentials.ProjectConnectionCredentialsManager
import org.testng.Assert.assertEquals
import org.testng.Assert.assertFalse
import org.testng.Assert.assertNotNull
import org.testng.Assert.assertSame
import org.testng.Assert.assertTrue
import org.testng.Assert.fail
import org.testng.annotations.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Test
class AwsConnectionCredentialsProviderTest {
    private val project: SProject = proxy(SProject::class.java)

    private class FakeManager(var result: () -> Map<String, String>) : ProjectConnectionCredentialsManager {
        val requests = AtomicInteger()
        var lastProject: SProject? = null
        var lastConnectionId: String? = null

        override fun requestConnectionCredentials(project: SProject, connectionId: String): ConnectionCredentials =
                requestConnectionCredentials(project, connectionId, null)

        override fun requestConnectionCredentials(project: SProject, connectionId: String, additionalParameters: MutableMap<String, String>?): ConnectionCredentials {
            requests.incrementAndGet()
            lastProject = project
            lastConnectionId = connectionId
            val props = result()
            return object : ConnectionCredentials {
                override fun getProperties(): MutableMap<String, String> = props.toMutableMap()
                override fun getProviderType(): String = "AWS"
            }
        }
    }

    private fun projects(known: Map<String, SProject>, externalIds: Map<String, SProject> = emptyMap()): ProjectManager =
            Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ProjectManager::class.java)) { _, method, args ->
                when (method.name) {
                    "findProjectById" -> known[args[0] as String]
                    "findProjectByExternalId" -> externalIds[args[0] as String]
                    else -> throw UnsupportedOperationException(method.name)
                }
            } as ProjectManager

    private fun <T> proxy(type: Class<T>): T =
            type.cast(Proxy.newProxyInstance(javaClass.classLoader, arrayOf(type)) { _, method, _ -> throw UnsupportedOperationException(method.name) })

    private val sessionCredentials = mapOf(
            "awsAccessKeyId" to "AKIATEST",
            "awsSecretAccessKey" to "secret",
            "awsSessionToken" to "token",
            "awsRegionName" to "us-east-1")

    @Test
    fun returnsSessionCredentialsOfConnection() {
        val manager = FakeManager { sessionCredentials }
        val provider = AwsConnectionCredentialsProvider(manager, projects(mapOf("Proj" to project)), "Proj", "AwsServices")

        val credentials = provider.credentials

        assertEquals(credentials.awsAccessKeyId, "AKIATEST")
        assertEquals(credentials.awsSecretKey, "secret")
        assertEquals((credentials as AWSSessionCredentials).sessionToken, "token")
        assertSame(manager.lastProject, project)
        assertEquals(manager.lastConnectionId, "AwsServices")
    }

    @Test
    fun returnsBasicCredentialsWhenConnectionHasNoSessionToken() {
        val manager = FakeManager { sessionCredentials - "awsSessionToken" }
        val provider = AwsConnectionCredentialsProvider(manager, projects(mapOf("Proj" to project)), "Proj", "AwsServices")

        val credentials = provider.credentials

        assertEquals(credentials.awsAccessKeyId, "AKIATEST")
        assertFalse(credentials is AWSSessionCredentials)
    }

    @Test
    fun reusesCredentialsUntilTheyAreDueForRefresh() {
        val manager = FakeManager { sessionCredentials }
        var now = 0L
        val provider = AwsConnectionCredentialsProvider(manager, projects(mapOf("Proj" to project)), "Proj", "AwsServices") { now }

        val first = provider.credentials
        now += TimeUnit.MINUTES.toNanos(4)
        assertSame(provider.credentials, first)
        assertEquals(manager.requests.get(), 1)

        now += TimeUnit.MINUTES.toNanos(2)
        provider.credentials
        assertEquals(manager.requests.get(), 2)
    }

    @Test
    fun refreshForcesNewRequest() {
        val manager = FakeManager { sessionCredentials }
        val provider = AwsConnectionCredentialsProvider(manager, projects(mapOf("Proj" to project)), "Proj", "AwsServices")

        provider.credentials
        provider.refresh()
        provider.credentials

        assertEquals(manager.requests.get(), 2)
    }

    @Test
    fun concurrentCallersShareOneRequest() {
        val manager = FakeManager { Thread.sleep(100); sessionCredentials }
        val provider = AwsConnectionCredentialsProvider(manager, projects(mapOf("Proj" to project)), "Proj", "AwsServices")
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val futures = (1..8).map { pool.submit<Any> { start.await(); provider.credentials } }
        start.countDown()
        futures.forEach { assertNotNull(it.get(10, TimeUnit.SECONDS)) }
        pool.shutdown()

        assertEquals(manager.requests.get(), 1)
    }

    @Test
    fun failsClearlyWhenConnectionIsNotAccessible() {
        val manager = FakeManager { throw ConnectionCredentialsException("Cannot access the 'Services' AWS connection. Check connection settings and ensure it is shared with child subprojects.") }
        val provider = AwsConnectionCredentialsProvider(manager, projects(mapOf("Proj" to project)), "Proj", "AwsServices")

        try {
            provider.credentials
            fail("expected SdkClientException")
        } catch (e: SdkClientException) {
            assertTrue(e.message!!.contains("AwsServices"))
            assertTrue(e.message!!.contains("shared with child subprojects"))
            assertTrue(e.cause is ConnectionCredentialsException)
        }
    }

    @Test
    fun findsProjectByExternalIdWhenInternalIdIsUnknown() {
        val manager = FakeManager { sessionCredentials }
        val provider = AwsConnectionCredentialsProvider(manager, projects(emptyMap(), mapOf("SubProj" to project)), "SubProj", "AwsServices")

        assertEquals(provider.credentials.awsAccessKeyId, "AKIATEST")
        assertSame(manager.lastProject, project)
    }

    @Test
    fun failsWhenProjectIsUnknown() {
        val provider = AwsConnectionCredentialsProvider(FakeManager { sessionCredentials }, projects(emptyMap()), "Gone", "AwsServices")

        try {
            provider.credentials
            fail("expected SdkClientException")
        } catch (e: SdkClientException) {
            assertTrue(e.message!!.contains("Gone"))
        }
    }

    @Test
    fun failsWhenConnectionReturnsNoKeys() {
        val provider = AwsConnectionCredentialsProvider(FakeManager { mapOf("awsRegionName" to "us-east-1") }, projects(mapOf("Proj" to project)), "Proj", "AwsServices")

        try {
            provider.credentials
            fail("expected SdkClientException")
        } catch (e: SdkClientException) {
            assertTrue(e.message!!.contains("no access key"))
        }
    }

    @Test
    fun factoryUsesConnectionWhenProfileHasOne() {
        val factory = EcsCredentialsProviderFactory(FakeManager { sessionCredentials }, projects(mapOf("Proj" to project)))

        val provider = factory.create(mapOf(AWS_CONNECTION_ID_PARAM to " AwsServices "), "Proj")

        assertTrue(provider is AwsConnectionCredentialsProvider)
        assertEquals(provider.credentials.awsAccessKeyId, "AKIATEST")
    }

    @Test
    fun factoryRequiresProjectForConnection() {
        val factory = EcsCredentialsProviderFactory(FakeManager { sessionCredentials }, projects(mapOf("Proj" to project)))

        try {
            factory.create(mapOf(AWS_CONNECTION_ID_PARAM to "AwsServices"), null)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("AwsServices"))
        }
    }
}
