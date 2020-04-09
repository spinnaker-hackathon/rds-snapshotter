package oss.plugin.stage.snapshotter.rds

import com.netflix.spinnaker.kork.plugins.api.ExtensionConfiguration

/**
 * Data in this class maps to the plugin configuration in a service's config YAML.
 * The data can be key/value pairs or an entire configuration tree.
 *
 */
@ExtensionConfiguration("oss.rdsSnapshotterStage")
data class RDSConfig(var defaultMaxWaitTime: Int)
