package com.reclamos.backend.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    @Test
    void removesEmailsLinksAndPhones() {
        String result = PiiRedactor.redact(
                "Escribime a vecina@mail.com o al 11 5555-1234, mirá https://sitio.com/foto o www.otra.com/x");

        assertThat(result).doesNotContain("vecina@mail.com", "5555", "sitio.com", "otra.com");
        assertThat(result).contains("[email]", "[número]", "[enlace]");
    }

    @Test
    void removesDocumentNumbersWithAndWithoutDots() {
        assertThat(PiiRedactor.redact("Mi DNI es 30.123.456")).contains("[documento]").doesNotContain("30.123");
        assertThat(PiiRedactor.redact("DNI 30123456")).contains("[número]").doesNotContain("30123456");
    }

    @Test
    void removesLongNumbersLikeCbuOrCards() {
        assertThat(PiiRedactor.redact("CBU 0170099220000067797370")).doesNotContain("0170099220000067797370");
    }

    @Test
    void keepsAddressesAndShortNumbersTheClassificationNeeds() {
        String text = "Hay un pozo en Av. Corrientes 1234, entre 9 de Julio y Callao, desde hace 3 días";

        assertThat(PiiRedactor.redact(text)).isEqualTo(text);
    }

    @Test
    void keepsDates() {
        assertThat(PiiRedactor.redact("Pasó el 2026-11-13 y el 13/11/2026")).isEqualTo("Pasó el 2026-11-13 y el 13/11/2026");
    }

    @Test
    void nullBecomesEmpty() {
        assertThat(PiiRedactor.redact(null)).isEmpty();
    }
}
