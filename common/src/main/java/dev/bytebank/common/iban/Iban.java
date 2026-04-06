package dev.bytebank.common.iban;

import java.math.BigInteger;
import java.util.Locale;

/**
 * Utilidades de IBAN español (ES + 2 dígitos de control + 20 dígitos). ByteBank usa la entidad {@value #BANK_CODE}.
 */
public final class Iban {

    public static final String BANK_CODE = "3999";
    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    private Iban() {
    }

    /** Quita espacios y pasa a mayúsculas. */
    public static String normalize(String iban) {
        return iban == null ? null : iban.replace(" ", "").toUpperCase(Locale.ROOT);
    }

    /** Comprueba que el IBAN es un IBAN español válido. */
    public static boolean isValid(String iban) {
        String value = normalize(iban);
        return value != null && value.matches("ES\\d{22}");
    }

    public static boolean isOwnBank(String iban) {
        return isValid(iban) && normalize(iban).startsWith(BANK_CODE, 4);
    }

    /** Genera un IBAN de ByteBank a partir de un número de cuenta. */
    public static String generate(long accountNumber) {
        String bban = BANK_CODE + "0001" + "00" + String.format("%010d", accountNumber);
        int check = 98 - mod97(bban + "ES00");
        return "ES" + String.format("%02d", check) + bban;
    }

    private static int mod97(String rearranged) {
        StringBuilder digits = new StringBuilder();
        for (char c : rearranged.toCharArray()) {
            digits.append(Character.isLetter(c) ? String.valueOf(c - 'A' + 10) : String.valueOf(c));
        }
        return new BigInteger(digits.toString()).mod(NINETY_SEVEN).intValue();
    }
}
