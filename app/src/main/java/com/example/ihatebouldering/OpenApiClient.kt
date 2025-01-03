package com.example.ihatebouldering

import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig

object OpenAIClient {

    private const val OPENAI_API_KEY = "<token>" // Replace with your actual API key

    val openAI: OpenAI by lazy {
        val config = OpenAIConfig(
            token = OPENAI_API_KEY,
        )
        OpenAI(config)
    }
}
