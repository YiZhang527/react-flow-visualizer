package com.github.reactflow.model

data class ReactComponent(
    val name: String,
    val filePath: String,
    val lineNumber: Int,
    val hasUseState: Boolean,
    val props: List<String>,
    val children: MutableList<ReactComponent> = mutableListOf()
)

data class ComponentGraph(
    val root: ReactComponent?,
    val allComponents: Map<String, ReactComponent>
)
