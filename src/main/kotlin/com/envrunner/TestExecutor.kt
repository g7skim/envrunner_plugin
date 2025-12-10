package com.envrunner

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.project.Project

object TestExecutor {

    fun createProcessHandler(project: Project, cmd: String, workDir: String?): OSProcessHandler {
        val command = GeneralCommandLine()
            .withExePath("/bin/sh")
            .withParameters("-c", cmd)
            .withWorkDirectory(workDir ?: project.basePath)

        return OSProcessHandler(command)
    }
}
