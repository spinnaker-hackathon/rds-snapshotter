package oss.plugin.stage.snapshotter.rds

import com.netflix.spinnaker.orca.api.simplestage.SimpleStage
import com.netflix.spinnaker.orca.api.simplestage.SimpleStageInput
import com.netflix.spinnaker.orca.api.simplestage.SimpleStageOutput
import com.netflix.spinnaker.orca.api.simplestage.SimpleStageStatus
import org.slf4j.LoggerFactory
import org.pf4j.Extension
import org.pf4j.Plugin
import org.pf4j.PluginWrapper


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

        stageOutput.setOutput(output)
        stageOutput.setContext(context)
        stageOutput.setStatus(SimpleStageStatus.SUCCEEDED)

        return stageOutput
    }
}
