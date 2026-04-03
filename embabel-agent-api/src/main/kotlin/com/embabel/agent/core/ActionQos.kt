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
package com.embabel.agent.core

import com.embabel.agent.api.tool.ToolControlFlowSignal
import com.embabel.agent.spi.common.ActionRetryListener
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.LlmDataBindingProperties.Companion.isRateLimitError
import com.embabel.common.util.loggerFor
import org.springframework.retry.RetryCallback
import org.springframework.retry.RetryContext
import org.springframework.retry.RetryListener
import org.springframework.retry.support.RetryTemplate
import java.time.Duration

/**
 * Quality of service requirements for an action
 */
data class ActionQos(
    override val maxAttempts: Int = 5,
    override val backoffMillis: Long = 10000,
    override val backoffMultiplier: Double = 5.0,
    override val backoffMaxInterval: Long = 60000,
    val idempotent: Boolean = false,
) : RetryProperties {

    fun retryTemplate(name: String, agentProcess: AgentProcess, actionRetryListener: ActionRetryListener?): RetryTemplate {
        return RetryTemplate.builder()
            .exponentialBackoff(
                Duration.ofMillis(backoffMillis),
                backoffMultiplier,
                Duration.ofMillis(backoffMaxInterval)
            )
            .customPolicy(retryPolicy)
            .withListener(object : RetryListener {
                override fun <T, E : Throwable> onError(
                    context: RetryContext,
                    callback: RetryCallback<T, E>,
                    throwable: Throwable,
                ) {
                    actionRetryListener?.onActionRetry(context, throwable, agentProcess)

                    // ToolControlFlowSignal exceptions (ReplanRequestedException, UserInputRequiredException, etc.)
                    // are control flow signals, not errors to retry - rethrow to abort retry
                    if (throwable is ToolControlFlowSignal) {
                        throw throwable
                    }
                    if (isRateLimitError(throwable)) {
                        loggerFor<RetryProperties>().info(
                            "LLM invocation {} RATE LIMITED: Retry attempt {} of {}",
                            name,
                            context.retryCount,
                            if (retryPolicy.maxAttempts > 0) retryPolicy.maxAttempts else "unknown",
                        )
                        return
                    }
                    loggerFor<RetryProperties>().info(
                        "Operation $name: Retry error. Retry count: ${context.retryCount}",
                        throwable,
                    )
                }
            })
            .build()
    }

}
