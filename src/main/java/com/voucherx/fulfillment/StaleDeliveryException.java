package com.voucherx.fulfillment;

/** 失效投递不会将原业务请求判为失败；恢复后以原请求重新投递。 */
public class StaleDeliveryException extends RuntimeException {
}
