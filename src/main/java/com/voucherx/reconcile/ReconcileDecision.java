package com.voucherx.reconcile;
import com.voucherx.fulfillment.RequestRecord;
/** 超时负责触发检查；动作以持久化终态、身份和预留归属为依据。 */
public enum ReconcileDecision {
    SYNC_RESULT, ACCEPT_RESERVATION, RELEASE_RESERVATION, REPLAY_EVENT, WAIT, ISOLATE;
    public static ReconcileDecision decide(RequestRecord r,boolean hasOrder,boolean orderMatches,boolean reservationMatches,String outboxState) {
        if(hasOrder) return orderMatches && "SUCCEEDED".equals(r.phase())?SYNC_RESULT:ISOLATE;
        if("SUCCEEDED".equals(r.phase())) return ISOLATE;
        if("INTENT".equals(r.phase())) return reservationMatches?ACCEPT_RESERVATION:ISOLATE;
        if("CLOSED".equals(r.phase())) return "RESERVED".equals(r.reservation())?RELEASE_RESERVATION:WAIT;
        if("ACCEPTED".equals(r.phase())) {
            if(outboxState==null || "QUARANTINED".equals(outboxState)) return ISOLATE;
            return "SENT".equals(outboxState)?REPLAY_EVENT:WAIT;
        }
        return ISOLATE;
    }
}
