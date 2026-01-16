package me.cortex.voxy.client.core;

import me.cortex.voxy.client.core.gl.GlBuffer;
import me.cortex.voxy.client.core.gl.GlTexture;
import me.cortex.voxy.client.core.model.ModelBakerySubsystem;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.rendering.hierachical.NodeCleaner;
import me.cortex.voxy.client.core.rendering.post.FullscreenBlit;
import me.cortex.voxy.client.core.rendering.section.backend.AbstractSectionRenderer;
import me.cortex.voxy.client.core.rendering.util.DepthFramebuffer;
import me.cortex.voxy.client.core.rendering.util.UploadStream;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31.GL_UNIFORM_BUFFER;
import static org.lwjgl.opengl.GL45C.*;

public class IrisVoxyRenderPipeline extends AbstractRenderPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(IrisVoxyRenderPipeline.class);

    private final IrisVoxyRenderPipelineData data;
    private final FullscreenBlit depthBlit;

    public final DepthFramebuffer fb;
    public final DepthFramebuffer fbTranslucent;

    private final GlBuffer shaderUniforms;

    private int lastWidth = -1;
    private int lastHeight = -1;
    private int[] lastOpaqueDrawTargets = null;
    private int[] lastTranslucentDrawTargets = null;

    public IrisVoxyRenderPipeline(IrisVoxyRenderPipelineData data, AsyncNodeManager nodeManager,
                                  NodeCleaner nodeCleaner, HierarchicalOcclusionTraverser traversal,
                                  BooleanSupplier frexSupplier) {
        super(nodeManager, nodeCleaner, traversal, frexSupplier);
        this.data = data;

        if (this.data.thePipeline != null) {
            throw new IllegalStateException("Pipeline data already bound");
        }
        this.data.thePipeline = this;

        this.depthBlit = new FullscreenBlit("voxy:post/blit_texture_depth_cutout.frag");

        this.fb = new DepthFramebuffer(GL_DEPTH24_STENCIL8);
        this.fbTranslucent = new DepthFramebuffer(GL_DEPTH24_STENCIL8);

        if (data.getUniforms() != null) {
            this.shaderUniforms = new GlBuffer(data.getUniforms().size());
        } else {
            this.shaderUniforms = null;
        }
    }

    @Override
    public void setupExtraModelBakeryData(ModelBakerySubsystem modelService) {
        modelService.factory.setCustomBlockStateMapping(WorldRenderingSettings.INSTANCE.getBlockStateIds());
    }

    @Override
    public void free() {
        if (this.data.thePipeline != this) {
            throw new IllegalStateException();
        }
        this.data.thePipeline = null;

        this.depthBlit.delete();
        this.fb.free();
        this.fbTranslucent.free();

        if (this.shaderUniforms != null) {
            this.shaderUniforms.free();
        }

        super.free0();
    }

    @Override
    public void preSetup(Viewport<?> viewport) {
        super.preSetup(viewport);

        if (this.shaderUniforms != null) {
            long ptr = UploadStream.INSTANCE.uploadTo(this.shaderUniforms);
            this.data.getUniforms().updater().accept(ptr);
            UploadStream.INSTANCE.commit();
        }
    }

    @Override
    protected int setup(Viewport<?> viewport, int sourceFramebuffer, int srcWidth, int srcHeight) {
        int viewportWidth = viewport.width;
        int viewportHeight = viewport.height;

        if (viewportWidth <= 0 || viewportHeight <= 0) {
            LOGGER.warn("Viewport尺寸无效: {}x{}，跳过帧缓冲设置", viewportWidth, viewportHeight);
            return 0;
        }

        boolean sizeChanged = (viewportWidth != lastWidth || viewportHeight != lastHeight);
        boolean opaqueAttachmentsChanged = attachmentsChanged(this.data.opaqueDrawTargets, lastOpaqueDrawTargets);
        boolean translucentAttachmentsChanged = attachmentsChanged(this.data.translucentDrawTargets, lastTranslucentDrawTargets);

        if (sizeChanged || opaqueAttachmentsChanged) {
            boolean resizeSuccess = this.fb.resize(viewportWidth, viewportHeight);
            if (!resizeSuccess) {
                LOGGER.error("不透明帧缓冲resize失败！");
                return 0;
            }

            attachColorAttachments(this.fb, this.data.opaqueDrawTargets);
            try {
                this.fb.framebuffer.verify();
            } catch (IllegalStateException e) {
                LOGGER.error("不透明帧缓冲验证失败", e);
                throw e;
            }

            lastWidth = viewportWidth;
            lastHeight = viewportHeight;
            lastOpaqueDrawTargets = copyArray(this.data.opaqueDrawTargets);
        }

        if (sizeChanged || translucentAttachmentsChanged) {
            boolean resizeSuccess = this.fbTranslucent.resize(viewportWidth, viewportHeight);
            if (!resizeSuccess) {
                LOGGER.error("半透明帧缓冲resize失败！");
                return 0;
            }

            attachColorAttachments(this.fbTranslucent, this.data.translucentDrawTargets);
            try {
                this.fbTranslucent.framebuffer.verify();
            } catch (IllegalStateException e) {
                LOGGER.error("半透明帧缓冲验证失败", e);
                throw e;
            }

            lastTranslucentDrawTargets = copyArray(this.data.translucentDrawTargets);
        }

        if (!this.data.useViewportDims) {
            srcWidth = viewportWidth;
            srcHeight = viewportHeight;
        }

        this.initDepthStencil(sourceFramebuffer, this.fb.framebuffer.id,
                srcWidth, srcHeight, viewportWidth, viewportHeight);

        GlTexture depthTex = this.fb.getDepthTex();
        if (depthTex == null || depthTex.id == 0) {
            LOGGER.error("深度纹理无效！");
            return 0;
        }
        return depthTex.id;
    }

    private void attachColorAttachments(DepthFramebuffer framebuffer, int[] drawTargets) {
        if (framebuffer == null || framebuffer.framebuffer.id == 0) {
            LOGGER.warn("尝试给无效帧缓冲绑定颜色附件，跳过");
            return;
        }

        if (drawTargets == null || drawTargets.length == 0) {
            glNamedFramebufferDrawBuffers(framebuffer.framebuffer.id, GL_NONE);
            return;
        }

        int[] bindings = new int[drawTargets.length];
        int validAttachmentCount = 0;
        for (int i = 0; i < drawTargets.length; i++) {
            bindings[i] = GL30.GL_COLOR_ATTACHMENT0 + i;
            int textureId = drawTargets[i];
            if (textureId != 0) {
                glNamedFramebufferTexture(framebuffer.framebuffer.id, bindings[i], textureId, 0);
                validAttachmentCount++;
            } else {
                LOGGER.warn("颜色附件{}的纹理ID为0（无效），跳过绑定", i);
                glNamedFramebufferTexture(framebuffer.framebuffer.id, bindings[i], 0, 0);
            }
        }

        if (validAttachmentCount == 0) {
            glNamedFramebufferDrawBuffers(framebuffer.framebuffer.id, GL_NONE);
            LOGGER.warn("没有有效颜色附件，设置绘制缓冲为GL_NONE");
        } else {
            glNamedFramebufferDrawBuffers(framebuffer.framebuffer.id, bindings);
        }
    }

    private boolean attachmentsChanged(int[] current, int[] last) {
        if (current == null && last == null) return false;
        if (current == null || last == null) return true;
        if (current.length != last.length) return true;

        for (int i = 0; i < current.length; i++) {
            if (current[i] != last[i]) return true;
        }
        return false;
    }

    private int[] copyArray(int[] source) {
        if (source == null) return new int[0];
        int[] copy = new int[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    @Override
    protected void postOpaquePreTranslucent(Viewport<?> viewport) {
        int mask = GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT;

        boolean copyColor = true;
        if (!copyColor) {
            mask |= GL_COLOR_BUFFER_BIT;
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, this.fbTranslucent.framebuffer.id);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
        }

        glBlitNamedFramebuffer(this.fb.framebuffer.id, this.fbTranslucent.framebuffer.id,
                0, 0, viewport.width, viewport.height,
                0, 0, viewport.width, viewport.height,
                mask, GL_NEAREST);
    }

    @Override
    protected void finish(Viewport<?> viewport, int sourceFrameBuffer, int srcWidth, int srcHeight) {
        boolean canDepthBlit = this.data.renderToVanillaDepth &&
                srcWidth == viewport.width &&
                srcHeight == viewport.height;

        if (canDepthBlit) {
            glColorMask(false, false, false, false);

            Matrix4f projectionMatrix = new Matrix4f(viewport.vanillaProjection).mul(viewport.modelView);
            AbstractRenderPipeline.transformBlitDepth(this.depthBlit,
                    this.fbTranslucent.getDepthTex().id, sourceFrameBuffer,
                    viewport, projectionMatrix);

            glColorMask(true, true, true, true);
        } else {
            glDisable(GL_STENCIL_TEST);
            glDisable(GL_DEPTH_TEST);
        }
    }

    @Override
    public void bindUniforms() {
        this.bindUniforms(UNIFORM_BINDING_POINT);
    }

    @Override
    public void bindUniforms(int bindingPoint) {
        if (this.shaderUniforms != null) {
            GL30.glBindBufferBase(GL_UNIFORM_BUFFER, bindingPoint, this.shaderUniforms.id);
        }
    }

    private void doBindings() {
        this.bindUniforms();

        if (this.data.getSsboSet() != null) {
            this.data.getSsboSet().bindingFunction().accept(10);
        }
        if (this.data.getImageSet() != null) {
            this.data.getImageSet().bindingFunction().accept(6);
        }
    }

    @Override
    public void setupAndBindOpaque(Viewport<?> viewport) {
        this.fb.bind();
        this.doBindings();
    }

    @Override
    public void setupAndBindTranslucent(Viewport<?> viewport) {
        this.fbTranslucent.bind();
        this.doBindings();

        if (this.data.getBlender() != null) {
            this.data.getBlender().run();
        }
    }

    @Override
    public void addDebug(List<String> debug) {
        debug.add("Using: " + this.getClass().getSimpleName());
        debug.add("Framebuffer Size: " + lastWidth + "x" + lastHeight);
        super.addDebug(debug);
    }

    private static final int UNIFORM_BINDING_POINT = 5;
    private static final int SSBO_BINDING_BASE = 10;
    private static final int IMAGE_BINDING_BASE = 6;

    private StringBuilder buildGenericShaderHeader(AbstractSectionRenderer<?, ?> renderer, String input) {
        StringBuilder builder = new StringBuilder(input).append("\n\n\n");

        if (this.data.getUniforms() != null) {
            builder.append("layout(binding = ").append(UNIFORM_BINDING_POINT)
                    .append(", std140) uniform ShaderUniformBindings ")
                    .append(this.data.getUniforms().layout())
                    .append(";\n\n");
        }

        if (this.data.getSsboSet() != null) {
            builder.append("#define BUFFER_BINDING_INDEX_BASE ").append(SSBO_BINDING_BASE).append("\n");
            builder.append(this.data.getSsboSet().layout()).append("\n\n");
        }

        if (this.data.getImageSet() != null) {
            builder.append("#define BASE_SAMPLER_BINDING_INDEX ").append(IMAGE_BINDING_BASE).append("\n");
            builder.append(this.data.getImageSet().layout()).append("\n\n");
        }

        return builder.append("\n\n");
    }

    @Override
    public String patchOpaqueShader(AbstractSectionRenderer<?, ?> renderer, String input) {
        var builder = this.buildGenericShaderHeader(renderer, input);
        builder.append(this.data.opaqueFragPatch());
        return builder.toString();
    }

    @Override
    public String patchTranslucentShader(AbstractSectionRenderer<?, ?> renderer, String input) {
        if (this.data.translucentFragPatch() == null) return null;

        var builder = this.buildGenericShaderHeader(renderer, input);
        builder.append(this.data.translucentFragPatch());
        return builder.toString();
    }

    @Override
    public String taaFunction(String functionName) {
        return this.taaFunction(UNIFORM_BINDING_POINT, functionName);
    }

    @Override
    public String taaFunction(int uboBindingPoint, String functionName) {
        var builder = new StringBuilder();

        if (this.data.getUniforms() != null) {
            builder.append("layout(binding = ").append(uboBindingPoint)
                    .append(", std140) uniform ShaderUniformBindings ")
                    .append(this.data.getUniforms().layout())
                    .append(";\n\n");
        }

        builder.append("vec2 ").append(functionName).append("()\n");
        builder.append(this.data.TAA);
        builder.append("\n");
        return builder.toString();
    }

    @Override
    public float[] getRenderScalingFactor() {
        return this.data.resolutionScale;
    }
}