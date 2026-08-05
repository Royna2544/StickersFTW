package com.royna.stickersftw.network.dto

import com.google.gson.annotations.SerializedName

/** Mirrors the server's uniform JSON error body: {"error_code": ..., "description": ...}. */
data class ErrorBodyDto(
    @SerializedName("error_code") val errorCode: Int? = null,
    val description: String? = null,
)
