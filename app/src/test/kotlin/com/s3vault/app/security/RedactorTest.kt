package com.s3vault.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    private val secret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"

    @Test fun `secret key assignments are masked`() {
        val out = Redactor.redact("failed with secret_access_key=$secret and more")
        assertFalse(out.contains(secret))
    }

    @Test fun `access key ids and tokens are masked`() {
        val out = Redactor.redact("key AKIAIOSFODNN7EXAMPLE x-amz-security-token: IQoJb3JpZ2luX2VjEXAMPLETOKENVALUE")
        assertFalse(out.contains("AKIAIOSFODNN7EXAMPLE"))
        assertFalse(out.contains("IQoJb3JpZ2luX2VjEXAMPLETOKENVALUE"))
    }

    @Test fun `authorization header and credential scope are masked`() {
        val out = Redactor.redact("Authorization: AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20261022/eu-west-1/s3/aws4_request, Signature=abcdef0123456789abcdef0123456789")
        assertFalse(out.contains("AKIAIOSFODNN7EXAMPLE"))
        assertFalse(out.contains("abcdef0123456789abcdef0123456789"))
    }

    @Test fun `any long base64-like blob is masked`() {
        assertFalse(Redactor.redact("blob $secret end").contains(secret))
    }

    @Test fun `harmless text is preserved`() {
        assertTrue(Redactor.redact("Error de red al subir IMG_0001.jpg").contains("IMG_0001.jpg"))
    }

    @Test fun `describe redacts exception text`() {
        val out = Redactor.describe(IllegalStateException("bad secret_access_key=$secret"))
        assertFalse(out.contains(secret))
    }
}
