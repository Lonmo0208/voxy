package me.cortex.voxy.client.core;

public interface IGetVoxyRenderSystem {
    VoxyRenderSystem getVoxyRenderSystem();
    VoxyRenderSystem getVoxyOverworldRenderSystem();
    void shutdownRenderer();
    void createRenderer();
}
