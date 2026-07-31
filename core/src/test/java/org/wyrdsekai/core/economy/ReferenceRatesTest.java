package org.wyrdsekai.core.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReferenceRatesTest {

    @Test
    void inference_small_rate() {
        // 1K tokens at 7B model = 1 CU
        assertEquals(1.0, ReferenceRates.calculate(
            ReferenceRates.SERVICE_INFERENCE_SMALL, 1.0));
    }

    @Test
    void inference_large_rate() {
        // 1K tokens at 70B model = 10 CU
        assertEquals(10.0, ReferenceRates.calculate(
            ReferenceRates.SERVICE_INFERENCE_LARGE, 1.0));
    }

    @Test
    void gpu_minute_rate() {
        // 1 minute GPU = 8 CU
        assertEquals(8.0, ReferenceRates.calculate(ReferenceRates.SERVICE_GPU, 1.0));
    }

    @Test
    void bandwidth_rate() {
        // 1 GB bandwidth = 2 CU
        assertEquals(2.0, ReferenceRates.calculate(ReferenceRates.SERVICE_BANDWIDTH, 1.0));
    }

    @Test
    void bilateral_multiplier_family_free() {
        // Family = 0x multiplier → free
        assertEquals(0.0, ReferenceRates.calculate(
            ReferenceRates.SERVICE_INFERENCE_SMALL, 100.0, 0.0));
    }

    @Test
    void bilateral_multiplier_partner_half() {
        // Partner = 0.5x multiplier
        assertEquals(50.0, ReferenceRates.calculate(
            ReferenceRates.SERVICE_INFERENCE_SMALL, 100.0, 0.5));
    }

    @Test
    void bilateral_multiplier_premium() {
        // Premium = 1.5x multiplier
        assertEquals(15.0, ReferenceRates.calculate(
            ReferenceRates.SERVICE_INFERENCE_SMALL, 10.0, 1.5));
    }

    @Test
    void negative_multiplier_clamped_to_zero() {
        assertEquals(0.0, ReferenceRates.calculate(
            ReferenceRates.SERVICE_GPU, 10.0, -1.0));
    }

    @Test
    void unknown_service_class_is_free() {
        assertEquals(0.0, ReferenceRates.calculate("unknown.service", 100.0));
    }

    @Test
    void inference_class_by_model_size() {
        assertEquals(ReferenceRates.SERVICE_INFERENCE_SMALL,
            ReferenceRates.inferenceServiceClass(4.0));
        assertEquals(ReferenceRates.SERVICE_INFERENCE_SMALL,
            ReferenceRates.inferenceServiceClass(7.0));
        assertEquals(ReferenceRates.SERVICE_INFERENCE_LARGE,
            ReferenceRates.inferenceServiceClass(13.0));
        assertEquals(ReferenceRates.SERVICE_INFERENCE_LARGE,
            ReferenceRates.inferenceServiceClass(70.0));
    }
}
