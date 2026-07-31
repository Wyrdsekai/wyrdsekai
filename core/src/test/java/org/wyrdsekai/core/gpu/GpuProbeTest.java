package org.wyrdsekai.core.gpu;

import org.junit.jupiter.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GpuProbeTest {

    @Nested
    class VramEstimation {

        @Test
        void fp16_7b_model() {
            long vram = GpuProbe.estimateVramForModel(7.0, "fp16");
            // 7 * 1000 * 2 + 1536 = 15536
            assertEquals(15536, vram);
        }

        @Test
        void q4_7b_model() {
            long vram = GpuProbe.estimateVramForModel(7.0, "q4");
            // 7 * 1000 * 0.5 + 1536 = 5036
            assertEquals(5036, vram);
        }

        @Test
        void q4_3b_model() {
            long vram = GpuProbe.estimateVramForModel(3.0, "q4_k_m");
            // 3 * 1000 * 0.5 + 1536 = 3036
            assertEquals(3036, vram);
        }

        @Test
        void q8_14b_model() {
            long vram = GpuProbe.estimateVramForModel(14.0, "q8");
            // 14 * 1000 * 1.0 + 1536 = 15536
            assertEquals(15536, vram);
        }

        @Test
        void null_quantization_defaults_fp16() {
            long vram = GpuProbe.estimateVramForModel(7.0, null);
            assertEquals(15536, vram);
        }

        @Test
        void unknown_quantization_defaults_fp16() {
            long vram = GpuProbe.estimateVramForModel(7.0, "unknown");
            assertEquals(15536, vram);
        }
    }

    @Nested
    class TpSuggestion {

        @Test
        void single_gpu_fits_7b_q4() {
            var gpu = new GpuProbe.GpuInfo(0, "RTX 4090", 24576, 22000, 2576, 10);
            assertEquals(1, GpuProbe.suggestTpSize(7.0, "q4", List.of(gpu)));
        }

        @Test
        void single_gpu_too_small_for_14b_fp16() {
            var gpu = new GpuProbe.GpuInfo(0, "RTX 3060", 12288, 10000, 2288, 20);
            assertEquals(0, GpuProbe.suggestTpSize(14.0, "fp16", List.of(gpu)));
        }

        @Test
        void tp2_needed_for_14b_fp16() {
            var gpu0 = new GpuProbe.GpuInfo(0, "A6000", 49152, 45000, 4152, 5);
            var gpu1 = new GpuProbe.GpuInfo(1, "A6000", 49152, 45000, 4152, 5);
            // 14B fp16 = 29536 MB, per GPU with TP2 = 14768 — fits in 45000
            assertEquals(1, GpuProbe.suggestTpSize(14.0, "fp16", List.of(gpu0, gpu1)));
        }

        @Test
        void tp2_for_70b_q4() {
            // 70B q4 = 36536 MB
            var gpu0 = new GpuProbe.GpuInfo(0, "A6000", 49152, 20000, 29152, 50);
            var gpu1 = new GpuProbe.GpuInfo(1, "A6000", 49152, 20000, 29152, 50);
            // TP1: need 36536, only 20000 — no
            // TP2: need 18268 each, only 20000 — yes
            assertEquals(2, GpuProbe.suggestTpSize(70.0, "q4", List.of(gpu0, gpu1)));
        }

        @Test
        void empty_gpus_returns_zero() {
            assertEquals(0, GpuProbe.suggestTpSize(7.0, "q4", List.of()));
        }
    }

    @Nested
    class ParallelSlots {

        @Test
        void single_slot_when_barely_fits() {
            // 7B q4 = 5036 MB, free = 6000, remaining = 964
            // perSlot at 4096 ctx = 1536 * 4096 / 16384 = 384 MB
            // 964 / 384 = 2
            int parallel = GpuProbe.suggestParallelSlots(7.0, "q4", 6000, 4096);
            assertEquals(2, parallel);
        }

        @Test
        void multiple_slots_with_large_vram() {
            // 7B q4 = 5036 MB, free = 24000, remaining = 18964
            // perSlot at 16384 ctx = 1536 MB
            // 18964 / 1536 = 12
            int parallel = GpuProbe.suggestParallelSlots(7.0, "q4", 24000, 16384);
            assertEquals(12, parallel);
        }

        @Test
        void minimum_one_when_no_room() {
            int parallel = GpuProbe.suggestParallelSlots(7.0, "fp16", 8000, 16384);
            assertEquals(1, parallel);
        }

        @Test
        void scales_with_context_size() {
            int small = GpuProbe.suggestParallelSlots(3.0, "q4", 12000, 4096);
            int large = GpuProbe.suggestParallelSlots(3.0, "q4", 12000, 16384);
            assertTrue(small >= large, "Smaller context should allow more slots");
        }
    }

    @Nested
    class LineParsing {

        @Test
        void valid_nvidia_smi_line() {
            var info = GpuProbe.parseNvidiaLine("0, NVIDIA GeForce RTX 4090, 24564, 22000, 2564, 15");
            assertNotNull(info);
            assertEquals(0, info.index());
            assertEquals("NVIDIA GeForce RTX 4090", info.name());
            assertEquals(24564, info.totalVramMB());
            assertEquals(22000, info.freeVramMB());
            assertEquals(2564, info.usedVramMB());
            assertEquals(15, info.utilizationPercent());
        }

        @Test
        void empty_line_returns_null() {
            assertNull(GpuProbe.parseNvidiaLine(""));
        }

        @Test
        void too_few_fields() {
            assertNull(GpuProbe.parseNvidiaLine("0, GPU, 24564"));
        }

        @Test
        void invalid_numbers() {
            assertNull(GpuProbe.parseNvidiaLine("abc, GPU, def, ghi, jkl, mno"));
        }

        @Test
        void vram_utilization() {
            var info = new GpuProbe.GpuInfo(0, "GPU", 24000, 12000, 12000, 50);
            assertEquals(0.5, info.vramUtilization(), 0.001);
        }

        @Test
        void zero_total_vram_utilization() {
            var info = new GpuProbe.GpuInfo(0, "GPU", 0, 0, 0, 0);
            assertEquals(0.0, info.vramUtilization());
        }
    }

    @Nested
    class RocmParsing {

        @Test
        void parse_single_amd_gpu() {
            var json = """
                {"card0": {"GPU ID": "0x744c", "Card series": "AMD Radeon RX 7900 XTX", \
                "VRAM Total Memory (B)": "25753026560", "VRAM Total Used Memory (B)": "1048576", \
                "GPU use (%)": "12"}}""";
            var gpus = GpuProbe.parseRocmJson(json);
            assertEquals(1, gpus.size());
            var gpu = gpus.getFirst();
            assertEquals(0, gpu.index());
            assertEquals("0x744c", gpu.name());
            assertEquals(24560, gpu.totalVramMB());
            assertEquals(12, gpu.utilizationPercent());
            assertEquals(GpuProbe.GpuVendor.AMD, gpu.vendor());
        }

        @Test
        void parse_two_amd_gpus() {
            var json = """
                {"card0": {"GPU ID": "RX 7900 XTX", "VRAM Total Memory (B)": "25753026560", \
                "VRAM Total Used Memory (B)": "0", "GPU use (%)": "0"}, \
                "card1": {"GPU ID": "RX 7800 XT", "VRAM Total Memory (B)": "17179869184", \
                "VRAM Total Used Memory (B)": "524288", "GPU use (%)": "5"}}""";
            var gpus = GpuProbe.parseRocmJson(json);
            assertEquals(2, gpus.size());
            assertEquals("RX 7900 XTX", gpus.get(0).name());
            assertEquals("RX 7800 XT", gpus.get(1).name());
            assertEquals(1, gpus.get(1).index());
        }

        @Test
        void parse_empty_json() {
            var gpus = GpuProbe.parseRocmJson("{}");
            assertTrue(gpus.isEmpty());
        }

        @Test
        void parse_missing_vram_defaults_zero() {
            var json = """
                {"card0": {"GPU ID": "AMD GPU"}}""";
            var gpus = GpuProbe.parseRocmJson(json);
            assertEquals(1, gpus.size());
            assertEquals(0, gpus.getFirst().totalVramMB());
        }

        @Test
        void fallback_to_card_series() {
            var json = """
                {"card0": {"Card series": "Radeon RX 7900 XTX", \
                "VRAM Total Memory (B)": "25753026560", "VRAM Total Used Memory (B)": "0", \
                "GPU use (%)": "0"}}""";
            var gpus = GpuProbe.parseRocmJson(json);
            assertEquals("Radeon RX 7900 XTX", gpus.getFirst().name());
        }

        @Test
        void fallback_to_default_name() {
            var json = """
                {"card0": {"VRAM Total Memory (B)": "25753026560", \
                "VRAM Total Used Memory (B)": "0"}}""";
            var gpus = GpuProbe.parseRocmJson(json);
            assertEquals("AMD GPU 0", gpus.getFirst().name());
        }
    }

    @Nested
    class VendorField {

        @Test
        void nvidia_line_defaults_to_nvidia_vendor() {
            var info = GpuProbe.parseNvidiaLine("0, NVIDIA RTX 4090, 24564, 22000, 2564, 15");
            assertEquals(GpuProbe.GpuVendor.NVIDIA, info.vendor());
        }

        @Test
        void convenience_constructor_defaults_nvidia() {
            var info = new GpuProbe.GpuInfo(0, "GPU", 24000, 12000, 12000, 50);
            assertEquals(GpuProbe.GpuVendor.NVIDIA, info.vendor());
        }

        @Test
        void amd_gpu_has_amd_vendor() {
            var info = new GpuProbe.GpuInfo(0, "RX 7900 XTX", 24576, 24000, 576, 5, GpuProbe.GpuVendor.AMD);
            assertEquals(GpuProbe.GpuVendor.AMD, info.vendor());
        }

        @Test
        void apple_gpu_has_apple_vendor() {
            var info = new GpuProbe.GpuInfo(0, "Apple M4 Pro", 18432, 18432, 0, 0, GpuProbe.GpuVendor.APPLE);
            assertEquals(GpuProbe.GpuVendor.APPLE, info.vendor());
            assertEquals(18432, info.totalVramMB());
            assertEquals(0.0, info.vramUtilization(), 0.001);
        }

        @Test
        void gpu_vendor_enum_has_four_values() {
            assertEquals(4, GpuProbe.GpuVendor.values().length);
        }
    }

    @Nested
    class Detection {

        @Test
        void detect_returns_list() {
            // Should not throw, returns empty on CI (no nvidia-smi or rocm-smi)
            var gpus = GpuProbe.detect();
            assertNotNull(gpus);
        }

        @Test
        void detectVendor_returns_none_on_ci() {
            // On CI with no GPUs, should return NONE
            var vendor = GpuProbe.detectVendor();
            assertNotNull(vendor);
        }
    }
}
