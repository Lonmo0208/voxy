package me.cortex.voxy.client.mixin.sodium;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.io.IOUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ShaderLoader.class, remap = false)
public class MixinShaderLoader {

    @Inject(
            method = "getShaderSource",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/lang/ClassLoader;getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;"
            ),
            cancellable = true
    )
    private static void onGetShaderSource(ResourceLocation name, CallbackInfoReturnable<String> cir) {
        String namespace, path;

        try {
            Class<?> resourceLocationClass = name.getClass();
            Method getNamespace = resourceLocationClass.getMethod("getNamespace");
            Method getPath = resourceLocationClass.getMethod("getPath");
            namespace = (String) getNamespace.invoke(name);
            path = (String) getPath.invoke(name);
        } catch (Exception e) {
            try {
                Method method12836 = name.getClass().getMethod("method_12836");
                Method method12832 = name.getClass().getMethod("method_12832");
                namespace = (String) method12836.invoke(name);
                path = (String) method12832.invoke(name);
            } catch (Exception e2) {
                return;
            }
        }

        String resourcePath = String.format("/assets/%s/shaders/%s", namespace, path);

        InputStream stream = null;

        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            stream = contextClassLoader.getResourceAsStream(resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath);
        }

        if (stream == null) {
            stream = ShaderLoader.class.getClassLoader().getResourceAsStream(resourcePath);
        }

        if (stream == null) {
            stream = ClassLoader.getSystemClassLoader().getResourceAsStream(resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath);
        }

        if (stream != null) {
            try {
                String source = IOUtils.toString(stream, StandardCharsets.UTF_8);
                stream.close();
                cir.setReturnValue(source);
                cir.cancel();
            } catch (IOException e) {
                throw new RuntimeException("Failed to read shader source for " + resourcePath, e);
            }
        }
    }
}