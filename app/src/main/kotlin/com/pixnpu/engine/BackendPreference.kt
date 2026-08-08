package com.pixnpu.engine

/**
 * User-selectable inference accelerator preference.
 *
 * - [Auto] — try NPU, then GPU, then CPU; degrade automatically on backend
 *   (dispatch) failures (see LiteRTLMEngine.degradeToGpu).
 * - [CPU]/[GPU]/[NPU] — pin the load to exactly that backend; no automatic
 *   degradation, so a failing backend surfaces the error instead of silently
 *   running on a different accelerator.
 */
enum class BackendPreference(val label: String) {
    Auto("Auto"),
    CPU("CPU"),
    GPU("GPU"),
    NPU("NPU"),
}
