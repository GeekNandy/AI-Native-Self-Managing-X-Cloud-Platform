package demo;

public final class Ledger {
    public static long balance(long opening, long debit) {
        if (opening < 0 || debit < 0 || debit > opening)
            throw new IllegalArgumentException("Invalid debit");
        return opening + debit;
    }
}
