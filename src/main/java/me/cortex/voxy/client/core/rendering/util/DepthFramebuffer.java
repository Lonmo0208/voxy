package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.opengl.ARBDirectStateAccess.nglClearNamedFramebufferfv;
import static org.lwjgl.opengl.GL11C.GL_DEPTH;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30C.*;

public class DepthFramebuffer {
    private final int depthType;
    private GlTexture depthBuffer;
    public final GlFramebuffer framebuffer;

    private int cachedWidth = -1;
    private int cachedHeight = -1;

    public DepthFramebuffer() {
        this(GL_DEPTH_COMPONENT24);
    }

    public DepthFramebuffer(int depthType) {
        this.depthType = depthType;
        this.framebuffer = new GlFramebuffer();
    }

    public boolean resize(int width, int height) {
        if (this.depthBuffer == null || width != cachedWidth || height != cachedHeight) {
            if (this.depthBuffer != null) {
                this.depthBuffer.free();
            }

            this.depthBuffer = new GlTexture().store(this.depthType, 1, width, height);

            int attachment = (this.depthType == GL_DEPTH24_STENCIL8) ?
                    GL_DEPTH_STENCIL_ATTACHMENT : GL_DEPTH_ATTACHMENT;
            this.framebuffer.bind(attachment, this.depthBuffer);

            cachedWidth = width;
            cachedHeight = height;

            return true;
        }
        return false;
    }

    public void clear() {
        this.clear(1.0f);
    }

    public void clear(float depth) {

        try (var stack = MemoryStack.stackPush()) {
            nglClearNamedFramebufferfv(this.framebuffer.id, GL_DEPTH, 0, stack.nfloat(depth));
        }
    }

    public GlTexture getDepthTex() {
        return this.depthBuffer;
    }

    public void free() {

        if (this.depthBuffer != null) {
            this.depthBuffer.free();
        }
        this.framebuffer.free();
    }

    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, this.framebuffer.id);
    }

    public int getWidth() {
        return cachedWidth;
    }

    public int getHeight() {
        return cachedHeight;
    }
}