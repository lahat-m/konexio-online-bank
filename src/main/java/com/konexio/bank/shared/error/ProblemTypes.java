package com.konexio.bank.shared.error;

import java.net.URI;

/** Builds the {@code type} URIs of RFC 9457 problem responses. */
public final class ProblemTypes {

    public static final String BASE_URI = "https://api.konexio.example/problems/";

    private ProblemTypes() {}

    public static URI of(String slug) {
        return URI.create(BASE_URI + slug);
    }
}
