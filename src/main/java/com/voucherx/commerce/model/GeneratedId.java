package com.voucherx.commerce.model;

/**
 * Receives the database-generated identity for an immutable consumption-order
 * draft.
 */
public class GeneratedId {
    private Long id;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
