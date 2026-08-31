package de.morau.nvidiadlss;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

final class BricksCompatibilityServerClassLoadTest {
    private static final String GATE_CLASS =
        "de.morau.nvidiadlss.BricksCompatibility";

    @Test
    void gateLoadsWithoutMinecraftClientOrBricksClasses()
            throws ReflectiveOperationException {
        ClassLoader loader = new ServerBoundaryLoader(
            BricksCompatibilityServerClassLoadTest.class.getClassLoader()
        );
        Class<?> gate = Class.forName(GATE_CLASS, true, loader);
        Method classifier = gate.getMethod("isBricksMixin", String.class);

        assertTrue((boolean) invoke(
            classifier,
            "de.morau.nvidiadlss.mixin.BricksCompositeBlockEntityDistanceMixin"
        ));
        assertFalse((boolean) invoke(
            classifier,
            "de.morau.nvidiadlss.mixin.GameRendererMixin"
        ));
        assertThrows(
            ClassNotFoundException.class,
            () -> loader.loadClass("net.minecraft.client.Minecraft")
        );
        assertThrows(
            ClassNotFoundException.class,
            () -> loader.loadClass(
                "com.matnx.omni.client.micro.CompositeBlockEntityRenderer"
            )
        );
    }

    private static Object invoke(Method method, String argument)
            throws ReflectiveOperationException {
        try {
            return method.invoke(null, argument);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof ReflectiveOperationException reflective) {
                throw reflective;
            }
            throw exception;
        }
    }

    private static final class ServerBoundaryLoader extends ClassLoader {
        private final ClassLoader source;

        private ServerBoundaryLoader(ClassLoader source) {
            super(source);
            this.source = source;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve)
                throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                if (
                    name.startsWith("net.minecraft.client.")
                        || name.startsWith("com.matnx.")
                ) {
                    throw new ClassNotFoundException(
                        "Rejected by dedicated-server boundary: " + name
                    );
                }
                if (!GATE_CLASS.equals(name)) {
                    return super.loadClass(name, resolve);
                }
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    loaded = defineGate(name);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }

        private Class<?> defineGate(String name) throws ClassNotFoundException {
            String resource = name.replace('.', '/') + ".class";
            try (InputStream input = source.getResourceAsStream(resource)) {
                if (input == null) {
                    throw new ClassNotFoundException(name);
                }
                byte[] bytes = input.readAllBytes();
                return defineClass(name, bytes, 0, bytes.length);
            } catch (IOException exception) {
                throw new ClassNotFoundException(name, exception);
            }
        }
    }
}
