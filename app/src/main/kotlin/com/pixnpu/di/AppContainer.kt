package com.pixnpu.di

import android.content.Context
import com.pixnpu.engine.LiteRTLMEngine
import com.pixnpu.engine.LiteRTLMEngineInterface
import com.pixnpu.model.ModelManager
import com.pixnpu.model.ModelManagerInterface

/**
 * Simple dependency injection container for the app.
 * Provides instances of the main components with their interfaces for testability.
 */
class AppContainer(private val context: Context) {
    
    /**
     * Provides the model manager instance
     */
    val modelManager: ModelManagerInterface by lazy {
        ModelManager(context.applicationContext)
    }
    
    /**
     * Provides the LiteRT-LM engine instance
     */
    val engine: LiteRTLMEngineInterface by lazy {
        LiteRTLMEngine(context.applicationContext)
    }
    
    /**
     * Provides the actual ModelManager for cases where the interface isn't sufficient
     */
    val rawModelManager: ModelManager by lazy {
        ModelManager(context.applicationContext)
    }
    
    /**
     * Provides the actual LiteRTLMEngine for cases where the interface isn't sufficient
     */
    val rawEngine: LiteRTLMEngine by lazy {
        LiteRTLMEngine(context.applicationContext)
    }
}

/**
 * Interface for the app container to enable testing with mock implementations
 */
interface AppContainerInterface {
    val modelManager: ModelManagerInterface
    val engine: LiteRTLMEngineInterface
}
