# AWS connection support (fork of JetBrains/teamcity-amazon-ecs-plugin @ 0b669cb)

An ECS cloud profile can now use a TeamCity **AWS connection** instead of its own credentials.

- New profile property `awsConnectionId` (same name the AWS Core plugin uses). The profile form has an
  "AWS connection" dropdown listing the connections available to the profile's project.
- When it is set, the legacy credential settings (access keys, temporary credentials, default chain) are ignored;
  the region is still required. When it is empty, behaviour is unchanged.
- Credentials come from `ProjectConnectionCredentialsManager.requestConnectionCredentials(project, connectionId)`,
  the same call the S3 artifact storage plugin makes. They are re-requested every 5 minutes, so IAM-role
  connections keep working after the first STS session expires.
- A connection defined in a parent project must have "Available for sub-projects" enabled, otherwise the profile
  shows the AWS Core error ("... ensure it is shared with child subprojects").
- No dependency on the AWS Core plugin's classes; only the server API (`connections-api-core`) is used.

## Build and test (no Gradle; see the header of build-awsconn.sh for inputs)

    ./build-awsconn.sh    # -> $WORK/build/out/aws-ecs.zip (plugin version gets the suffix -awsconn.1)
    ./test-awsconn.sh     # 11 TestNG tests on the server's Java 21
