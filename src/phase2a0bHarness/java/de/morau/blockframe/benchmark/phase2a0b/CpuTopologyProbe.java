package de.morau.blockframe.benchmark.phase2a0b;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort Windows topology probe executed once before WARMUP/MEASURE.
 * It uses built-in PowerShell/CIM only, changes no affinity or priority and
 * adds no runtime dependency.
 */
public final class CpuTopologyProbe {
    public interface OsProbe {
        Map<String, String> query() throws Exception;
    }

    private CpuTopologyProbe() {
    }

    public static CpuTopology detect() {
        return detect(new WindowsPowerShellProbe(Duration.ofSeconds(8)));
    }

    public static CpuTopology detect(OsProbe probe) {
        Objects.requireNonNull(probe, "probe");
        Map<String, String> values;
        String status = "AVAILABLE";
        try {
            values = probe.query();
        } catch (Exception error) {
            values = Map.of();
            status = "PARTIAL: " + error.getClass().getSimpleName();
        }
        int physical = positiveInt(values.get("physical"));
        int logical = positiveInt(values.get("logical"));
        double smt = physical > 0 && logical >= physical
            ? (double)logical / (double)physical
            : Double.NaN;
        String affinity = value(values, "affinity");
        int affinityCount = bitCount(affinity);
        int numa = positiveInt(values.get("numa"));
        return new CpuTopology(
            status,
            value(values, "model"),
            physical,
            logical,
            smt,
            Runtime.getRuntime().availableProcessors(),
            CpuTopology.NOT_AVAILABLE,
            numa,
            CpuTopology.NOT_AVAILABLE,
            affinity,
            affinityCount,
            System.getProperty("os.name", CpuTopology.NOT_AVAILABLE)
                + " "
                + System.getProperty(
                    "os.version",
                    CpuTopology.NOT_AVAILABLE
                ),
            System.getProperty(
                    "java.vm.name",
                    CpuTopology.NOT_AVAILABLE
                )
                + " "
                + System.getProperty(
                    "java.vm.version",
                    CpuTopology.NOT_AVAILABLE
                ),
            System.getProperty("java.version", CpuTopology.NOT_AVAILABLE)
        );
    }

    private static String value(Map<String, String> values, String key) {
        String value = values.get(key);
        return value == null || value.isBlank()
            ? CpuTopology.NOT_AVAILABLE
            : value.strip();
    }

    private static int positiveInt(String text) {
        if (text == null) {
            return -1;
        }
        try {
            int value = Integer.parseInt(text.strip());
            return value > 0 ? value : -1;
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    private static int bitCount(String affinity) {
        if (affinity == null || affinity.isBlank()) {
            return -1;
        }
        try {
            String value = affinity.strip();
            BigInteger mask = value.startsWith("0x")
                ? new BigInteger(value.substring(2), 16)
                : new BigInteger(value);
            return mask.signum() < 0 ? -1 : mask.bitCount();
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    static final class WindowsPowerShellProbe implements OsProbe {
        private final Duration timeout;

        WindowsPowerShellProbe(Duration timeout) {
            this.timeout = timeout;
        }

        @Override
        public Map<String, String> query() throws Exception {
            if (
                !System.getProperty("os.name", "")
                    .toLowerCase()
                    .contains("windows")
            ) {
                throw new IOException("not Windows");
            }
            long pid = ProcessHandle.current().pid();
            String script =
                "$c=@(Get-CimInstance Win32_Processor);"
                    + "$n=@(Get-CimInstance Win32_NumaNode "
                    + "-ErrorAction SilentlyContinue);"
                    + "$p=Get-Process -Id "
                    + pid
                    + ";"
                    + "'model=' + (($c|Select-Object -First 1).Name);"
                    + "'physical=' + (($c|Measure-Object NumberOfCores -Sum).Sum);"
                    + "'logical=' + (($c|Measure-Object NumberOfLogicalProcessors -Sum).Sum);"
                    + "'numa=' + $n.Count;"
                    + "'affinity=' + $p.ProcessorAffinity.ToInt64()";
            Process process = new ProcessBuilder(
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                script
            ).redirectErrorStream(true).start();
            if (
                !process.waitFor(
                    this.timeout.toMillis(),
                    TimeUnit.MILLISECONDS
                )
            ) {
                process.destroyForcibly();
                throw new IOException("PowerShell topology query timed out");
            }
            String output = new String(
                process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
            );
            Map<String, String> result = new HashMap<>();
            for (String line : output.lines().toList()) {
                int separator = line.indexOf('=');
                if (separator > 0) {
                    result.put(
                        line.substring(0, separator).strip(),
                        line.substring(separator + 1).strip()
                    );
                }
            }
            if (process.exitValue() != 0 || result.isEmpty()) {
                throw new IOException(
                    "PowerShell topology query failed: "
                        + process.exitValue()
                );
            }
            return result;
        }
    }
}
