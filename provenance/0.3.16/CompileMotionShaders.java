import static org.lwjgl.util.shaderc.Shaderc.shaderc_compilation_status_success;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_into_spv;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_add_macro_definition;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_optimization_level;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compile_options_set_target_env;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_initialize;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compiler_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_compute_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_env_version_vulkan_1_2;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_optimization_level_performance;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_bytes;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_compilation_status;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_get_error_message;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_result_release;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_target_env_vulkan;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Recreates the two SPIR-V files shipped in BlockFrame DLSS 0.3.16. */
public final class CompileMotionShaders {
    private CompileMotionShaders() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) {
            throw new IllegalArgumentException(
                "Usage: CompileMotionShaders <motion_vectors.comp> <output-directory>"
            );
        }

        Path sourcePath = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path outputDirectory = Path.of(arguments[1]).toAbsolutePath().normalize();
        Files.createDirectories(outputDirectory);
        String source = Files.readString(sourcePath, StandardCharsets.UTF_8);

        long compiler = shaderc_compiler_initialize();
        if (compiler == 0L) {
            throw new IllegalStateException("shaderc_compiler_initialize failed");
        }
        try {
            compile(
                compiler,
                source,
                "0",
                outputDirectory.resolve("motion_vectors.comp.spv")
            );
            compile(
                compiler,
                source,
                "1",
                outputDirectory.resolve("motion_vectors.debug.comp.spv")
            );
        } finally {
            shaderc_compiler_release(compiler);
        }
    }

    private static void compile(
        long compiler,
        String source,
        String diagnosticValue,
        Path output
    ) throws Exception {
        long options = shaderc_compile_options_initialize();
        if (options == 0L) {
            throw new IllegalStateException("shaderc_compile_options_initialize failed");
        }
        try {
            shaderc_compile_options_set_target_env(
                options,
                shaderc_target_env_vulkan,
                shaderc_env_version_vulkan_1_2
            );
            shaderc_compile_options_set_optimization_level(
                options,
                shaderc_optimization_level_performance
            );
            shaderc_compile_options_add_macro_definition(
                options,
                "BLOCKFRAME_DEVELOPER_DIAGNOSTICS",
                diagnosticValue
            );

            long result = shaderc_compile_into_spv(
                compiler,
                source,
                shaderc_compute_shader,
                "motion_vectors.comp",
                "main",
                options
            );
            if (result == 0L) {
                throw new IllegalStateException("shaderc_compile_into_spv failed");
            }
            try {
                int status = shaderc_result_get_compilation_status(result);
                if (status != shaderc_compilation_status_success) {
                    throw new IllegalStateException(
                        "shaderc failed: " + shaderc_result_get_error_message(result)
                    );
                }
                ByteBuffer bytecode = shaderc_result_get_bytes(result);
                byte[] bytes = new byte[bytecode.remaining()];
                bytecode.get(bytes);
                Files.write(output, bytes);
                System.out.println(output + " (" + bytes.length + " bytes)");
            } finally {
                shaderc_result_release(result);
            }
        } finally {
            shaderc_compile_options_release(options);
        }
    }
}
