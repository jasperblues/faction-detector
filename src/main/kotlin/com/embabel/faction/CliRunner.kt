/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.faction

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.annotation.Order
import org.springframework.shell.Input
import org.springframework.shell.InputProvider
import org.springframework.shell.Shell
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

/**
 * Enables one-shot CLI usage alongside the default interactive shell.
 *
 * The command is passed via the `-Dfaction.command` system property by the `faction`
 * wrapper script, which avoids Spring Boot's argument parser splitting `--repo`,
 * `--since` etc. into option keys and bare values.
 *
 * With no `faction.command` property set, the interactive Spring Shell starts normally.
 *
 * Usage (via wrapper script):
 *   ./faction analyse --repo nodejs/node --since 2018-06-01 --until 2020-01-01
 */
@Component
@Order(Int.MIN_VALUE)
class CliRunner(private val shell: Shell) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val cmd = System.getProperty("faction.command")?.trim()
        if (!cmd.isNullOrEmpty()) {
            var consumed = false
            shell.run(InputProvider {
                if (!consumed) {
                    consumed = true
                    Input { cmd }
                } else {
                    null
                }
            })
            exitProcess(0)
        }
    }
}