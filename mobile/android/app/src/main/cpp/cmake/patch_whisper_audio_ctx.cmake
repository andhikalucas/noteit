# Local patch for whisper.cpp 080bbbe85230f624f0b52127f1ae1218247989f9.
# whisper_full_with_state must set this call's context before language detection
# invokes whisper_encode_with_state. Otherwise it uses the preceding call's
# context, or the full model context on the first call.
# Run only at configure time, never against an actively measured baseline.
if(NOT DEFINED WHISPER_AUDIO_CTX_SOURCE OR NOT EXISTS "${WHISPER_AUDIO_CTX_SOURCE}")
    message(FATAL_ERROR "Missing pinned whisper.cpp source for audio-context patch")
endif()

set(_noteit_original_sha "00d02d64b6818ef24ab97cc2184794634668e7cb0343ede2791d78a3f651dc45")
set(_noteit_patched_sha "33679c9de7610775a8ff8c3cd307e35568aae82b888632cfd1a7d66f6982fbe6")
file(SHA256 "${WHISPER_AUDIO_CTX_SOURCE}" _noteit_source_sha)
if(_noteit_source_sha STREQUAL _noteit_patched_sha)
    return() # Idempotent for incremental configurations of the same source tree.
endif()
if(NOT _noteit_source_sha STREQUAL _noteit_original_sha)
    message(FATAL_ERROR "Whisper audio-context patch refused: source differs from pinned original/patched bytes")
endif()

file(READ "${WHISPER_AUDIO_CTX_SOURCE}" _noteit_source)
set(_noteit_audio_ctx_block [=[    // overwrite audio_ctx, max allowed is hparams.n_audio_ctx
    if (params.audio_ctx > whisper_n_audio_ctx(ctx)) {
        WHISPER_LOG_ERROR("%s: audio_ctx is larger than the maximum allowed (%d > %d)\n", __func__, params.audio_ctx, whisper_n_audio_ctx(ctx));
        return -5;
    }
    state->exp_n_audio_ctx = params.audio_ctx;

]=])
set(_noteit_detect_anchor "    // auto-detect language if not specified\n")
string(FIND "${_noteit_source}" "${_noteit_audio_ctx_block}" _noteit_block_index)
string(FIND "${_noteit_source}" "${_noteit_detect_anchor}" _noteit_detect_index)
if(_noteit_block_index LESS 0 OR _noteit_detect_index LESS 0)
    message(FATAL_ERROR "Whisper audio-context patch refused: expected anchors missing")
endif()

# Move the unmodified validation + assignment block, rather than duplicating it.
string(REPLACE "${_noteit_audio_ctx_block}" "" _noteit_patched "${_noteit_source}")
string(REPLACE "${_noteit_detect_anchor}" "${_noteit_audio_ctx_block}${_noteit_detect_anchor}"
    _noteit_patched "${_noteit_patched}")
string(SHA256 _noteit_result_sha "${_noteit_patched}")
if(NOT _noteit_result_sha STREQUAL _noteit_patched_sha)
    message(FATAL_ERROR "Whisper audio-context patch refused: patched bytes do not match expected result")
endif()
file(WRITE "${WHISPER_AUDIO_CTX_SOURCE}" "${_noteit_patched}")
message(STATUS "Applied pinned Whisper per-call audio-context initialization patch")
