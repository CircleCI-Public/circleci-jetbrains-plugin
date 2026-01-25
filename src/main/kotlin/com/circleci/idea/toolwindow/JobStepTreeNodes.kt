package com.circleci.idea.toolwindow

import com.circleci.idea.state.JobAction

/**
 * Data classes for job step tree nodes.
 */
data class StepNodeData(val name: String?, val stepNumber: Int)

data class ActionNodeData(val action: JobAction)
