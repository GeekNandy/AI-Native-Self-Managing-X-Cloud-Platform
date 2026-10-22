package demo;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LedgerTest {
    @Test
    void subtractsDebit() {
        assertEquals(75, Ledger.balance(100, 25));
    }

    @Test
    void emptyBalanceIsValid() {
        assertEquals(0, Ledger.balance(10, 10));
    }

    @Test
    void rejectsOverdraft() {
        assertThrows(IllegalArgumentException.class, () -> Ledger.balance(10, 11));
    }
}
