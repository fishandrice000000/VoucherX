package com.voucherx.commerce.model;

public sealed interface CommerceOrder permits PurchaseOrder, ConsumptionOrder {
    long id();

    long userId();

    String paymentStatus();

    long payAmount();

    long paymentDeadlineMs();
}
