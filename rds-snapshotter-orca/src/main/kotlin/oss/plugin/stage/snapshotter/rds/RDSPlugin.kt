package oss.plugin.stage.snapshotter.rds

import com.netflix.spinnaker.orca.api.simplestage.SimpleStage
import com.netflix.spinnaker.orca.api.simplestage.SimpleStageInput
import com.netflix.spinnaker.orca.api.simplestage.SimpleStageOutput
import com.netflix.spinnaker.orca.api.simplestage.SimpleStageStatus
import org.pf4j.Extension
import org.pf4j.Plugin
import org.pf4j.PluginWrapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.core.exception.SdkClientException
import software.amazon.awssdk.http.SdkHttpResponse
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.regions.RegionMetadata
import software.amazon.awssdk.services.rds.RdsClient
import software.amazon.awssdk.services.rds.RdsClientBuilder
import software.amazon.awssdk.services.rds.model.*
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse
import software.amazon.awssdk.services.sts.model.Credentials
import java.time.Instant
import java.util.*


class RandomWaitPlugin(wrapper: PluginWrapper) : Plugin(wrapper) {
    private val logger = LoggerFactory.getLogger(RandomWaitPlugin::class.java)

    override fun start() {
        logger.info("RdsSNapshotterPlugin.start()")
    }

    override fun stop() {
        logger.info("RdsSNapshotterPlugin.stop()")
    }
}

/**
 * By implementing SimpleStage, your stage is available for use in Spinnaker.
 * @see com.netflix.spinnaker.orca.api.SimpleStage
 */
@Extension
class RDSSnapshotterStage(val configuration: RDSConfig) : SimpleStage<RDSInput> {

    /**
     * AWS Vars
     */
    private val DEFAULT_SESSION_NAME = "Spinnaker"
    private val DEFAULT_ROLE_NAME = "spinnakerManaged-terraform"

    var AWS_ACCESS_KEY_ID: String? = null
    var AWS_SECRET_ACCESS_KEY: String? = null

    private lateinit var client: RdsClient

    private val log = LoggerFactory.getLogger(SimpleStage::class.java)

    /**
     * This sets the name of the stage
     * @return the name of the stage
     */
    override fun getName(): String {
        return "rdsSnapshotter"
    }

    /**
     * This is called when the stage is executed. It takes in an object that you create. That
     * object contains fields that you want to pull out of the pipeline context. This gives you a
     * strongly typed object that you have full control over.
     * The SimpleStageOutput class contains the status of the stage and any stage outputs that should be
     * put back into the pipeline context.
     * @param stageInput<RandomWaitInput>
     * @return SimpleStageOutput; the status of the stage and any context that should be passed to the pipeline context
     */
    override fun execute(stageInput: SimpleStageInput<RDSInput>): SimpleStageOutput<*, *> {

        val stageOutput = SimpleStageOutput<Output, Context>()
        val output = Output("test")
        val context = Context("test")


        val awsAccount: String = stageInput
                .value
                .awsAccount
        if (awsAccount.isEmpty()) {
            log.error("AWS Account must be specified for stage to function")
            stageOutput.status = SimpleStageStatus.TERMINAL
            context.errorMessage = "AWS Account must be specified"
            return stageOutput
        }

        val credentials: Optional<AwsSessionCredentials> = getCredentials(awsAccount)
        if (!credentials!!.isPresent()) {
            log.error("Unable to assume AWS Role to snapshot database")
            stageOutput.status = SimpleStageStatus.TERMINAL
            context.errorMessage = "Unable to assume AWS IAM role"
            return stageOutput
        }

        val awsRegionInput: String = stageInput.value.region
        var awsRegion: Region = Region.US_EAST_1 //hardcoded atm

        if (awsRegionInput != null && !awsRegionInput.isEmpty()) {
            awsRegion = Region.of(awsRegionInput)
            val metadata: RegionMetadata = RegionMetadata.of(awsRegion)
            if (metadata == null) {
                log.error(String.format("Invalid AWS region specified, please provide a valid region: %s", awsRegionInput))
                stageOutput.status = SimpleStageStatus.TERMINAL
                context.errorMessage = String.format("Invalid AWS region specified, please provide a valid region: %s", awsRegionInput)
                return stageOutput
            }
        }

        val rdsClient: RdsClientBuilder = RdsClient
                .builder()
                .region(awsRegion)
                .credentialsProvider(StaticCredentialsProvider.create(credentials.get()))

        this.client = rdsClient.build()

        val instanceName: String = stageInput.value.instanceName
        if (instanceName == null || instanceName.isEmpty()) {
            log.error("AWS Account must be specified for stage to function")
            stageOutput.status = SimpleStageStatus.TERMINAL
            context.errorMessage = "RDS instance name must be specified"
            return stageOutput
        }

        var snapshotName: String = stageInput.value.snapshotName
        if (snapshotName == null || snapshotName.isEmpty()) {
            val instant = Instant.now()
            val timeStampSeconds = instant.epochSecond
            snapshotName = String.format("%s-%s", instanceName, timeStampSeconds)
            log.info(String.format("Snapshot name not specified, using generated name: %s", snapshotName))
        }

        val snapshotResponse: SdkHttpResponse
        val isDBClustered = isDBClustered(instanceName)
        log.info(String.format("%s is DB cluster: %s", instanceName, isDBClustered))
        snapshotResponse = if (isDBClustered) {
            createClusterSnapshot(instanceName, snapshotName)
        } else {
            createInstanceSnapshot(instanceName, snapshotName)
        }

        stageOutput.setOutput(output)
        stageOutput.setContext(context)
        if (snapshotResponse.isSuccessful()) {
            log.info(String.format("Snapshot successfully started for %s", instanceName))
            stageOutput.setStatus(SimpleStageStatus.SUCCEEDED)
        } else {
            log.info(java.lang.String.format("Snapshot failed to start for %s\nError: %s", instanceName, snapshotResponse.statusText()))
            stageOutput.setStatus(SimpleStageStatus.TERMINAL)
        }

        return stageOutput
    }

    private fun createClusterSnapshot(clusterName: String, snapshotName: String): SdkHttpResponse {
        log.info(String.format("Requesting cluster snapshot for instance: %s with snapshot name: %s", clusterName, snapshotName))
        val request: CreateDbClusterSnapshotRequest = CreateDbClusterSnapshotRequest.builder()
                .dbClusterIdentifier(clusterName)
                .dbClusterSnapshotIdentifier(snapshotName)
                .build()
        val snapshotResponse: CreateDbClusterSnapshotResponse = client.createDBClusterSnapshot(request)
        return snapshotResponse.sdkHttpResponse()
    }

    private fun createInstanceSnapshot(instanceName: String, snapshotName: String): SdkHttpResponse {
        log.info(String.format("Requesting instance snapshot for instance: %s with snapshot name: %s", instanceName, snapshotName))
        val request: CreateDbSnapshotRequest = CreateDbSnapshotRequest.builder()
                .dbInstanceIdentifier(instanceName)
                .dbSnapshotIdentifier(snapshotName)
                .build()

        val snapshotResponse: CreateDbSnapshotResponse = client.createDBSnapshot(request)
        return snapshotResponse.sdkHttpResponse()
    }

    private fun isDBClustered(instanceName: String): Boolean {
        log.info(String.format("Searching for instance by db instance id: %s", instanceName))
        val describeFilters: MutableList<Filter> = ArrayList<Filter>()
        describeFilters.add(Filter.builder().name("db-instance-id").values(instanceName).build())
        val describeRequest: DescribeDbInstancesRequest = DescribeDbInstancesRequest.builder()
                .filters(describeFilters)
                .build()
        val instances: List<DBInstance> = client.describeDBInstances(describeRequest).dbInstances()
        return if (instances.size >= 1) {
            false
        } else true
    }

    private fun getCredentials(awsAccount: String): Optional<AwsSessionCredentials> {
        return try {
            log.debug(String.format("Key: %s, Secret: %s", AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY))
            val awsCreds: AwsBasicCredentials = AwsBasicCredentials.create(AWS_ACCESS_KEY_ID,
                    AWS_SECRET_ACCESS_KEY)
            val stsClient: StsClient = StsClient.builder().region(Region.US_EAST_1)
                    .credentialsProvider(StaticCredentialsProvider.create(awsCreds)).build()
            val assumeRole = String.format("arn:aws:iam::%s:role/%s", awsAccount, DEFAULT_ROLE_NAME)
            log.info(String.format("Assuming role: %s", assumeRole))
            val assumeRoleRequest: AssumeRoleRequest = AssumeRoleRequest.builder()
                    .roleArn(assumeRole).roleSessionName(DEFAULT_SESSION_NAME).build()
            val assumeRoleResponse: AssumeRoleResponse = stsClient.assumeRole(assumeRoleRequest)
            if (!assumeRoleResponse.sdkHttpResponse().isSuccessful()) {
                log.error(java.lang.String.format("Error assuming role %s, Error: %s", assumeRole, assumeRoleResponse.sdkHttpResponse().statusText()))
                return Optional.empty<AwsSessionCredentials?>()
            }
            log.info(java.lang.String.format("Successfully assumed role: %s", assumeRoleResponse.assumedRoleUser()))
            val sessionCredentials: Credentials = assumeRoleResponse.credentials()
            val sessionCreds: AwsSessionCredentials = AwsSessionCredentials.create(sessionCredentials.accessKeyId(),
                    sessionCredentials.secretAccessKey(), sessionCredentials.sessionToken())
            Optional.of<AwsSessionCredentials?>(sessionCreds)
        } catch (e: AwsServiceException) {
            e.printStackTrace()
            Optional.empty<AwsSessionCredentials?>()
        } catch (e: SdkClientException) {
            e.printStackTrace()
            Optional.empty<AwsSessionCredentials?>()
        }
    }
}
