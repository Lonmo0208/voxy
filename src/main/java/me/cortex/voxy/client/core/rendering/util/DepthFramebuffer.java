package me.cortex.voxy.client.core.rendering.util;

import me.cortex.voxy.client.core.gl.GlFramebuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.opengl.ARBDirectStateAccess.nglClearNamedFramebufferfv;
import static org.lwjgl.opengl.GL11C.GL_DEPTH;
import static org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24;
import static org.lwjgl.opengl.GL30C.*;

public class DepthFramebuffer {
    private static final Logger LOGGER = LoggerFactory.getLogger(DepthFramebuffer.class);

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
        if (width <= 0 || height <= 0) {
            LOGGER.warn("尝试调整深度帧缓冲到无效尺寸: {}x{}，跳过调整", width, height);
            return false;
        }

        if (this.depthBuffer == null || width != cachedWidth || height != cachedHeight) {
            if (this.depthBuffer != null) {
                this.depthBuffer.free();
            }

            this.depthBuffer = new GlTexture().store(this.depthType, 1, width, height);

            if (this.depthBuffer == null || this.depthBuffer.id == 0) {
                LOGGER.error("深度纹理创建失败！格式: {}, 尺寸: {}x{}", getDepthTypeName(this.depthType), width, height);
                return false;
            }

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
        if (this.framebuffer.id == 0) {
            LOGGER.warn("尝试清除无效的深度帧缓冲，跳过");
            return;
        }

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
        if (this.framebuffer.id != 0) {
            this.framebuffer.free();
        }
    }

    public void bind() {
        if (this.framebuffer.id == 0) {
            LOGGER.warn("尝试绑定无效的深度帧缓冲，跳过");
            return;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, this.framebuffer.id);
    }

    public int getWidth() {
        return cachedWidth;
    }

    public int getHeight() {
        return cachedHeight;
    }

    private String getDepthTypeName(int type) {
        return switch (type) {
            case GL_DEPTH_COMPONENT24 -> "GL_DEPTH_COMPONENT24";
            case GL_DEPTH24_STENCIL8 -> "GL_DEPTH24_STENCIL8";
            default -> "UNKNOWN(" + type + ")";
        };
    }
}