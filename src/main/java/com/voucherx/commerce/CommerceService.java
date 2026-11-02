package com.voucherx.commerce;

import com.voucherx.common.api.ErrorCode;
import com.voucherx.common.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CommerceService {
    public enum Kind {
        PURCHASE("tb_voucher_order"),CONSUMPTION("tb_consumption_order");
        final String table;Kind(String table) { this.table=table; }
    }
    public record Receipt(String eventId,String outcome,String orderState) {}
    private final JdbcTemplate db;private final Clock clock;private final CommerceProperties config;
    public CommerceService(JdbcTemplate db,Clock clock,CommerceProperties config) {this.db=db;this.clock=clock;this.config=config;}
    /** 在创建秒杀订单的现有事务中调用，固定付款及权益规则快照。 */
    public void initializePurchase(long orderId,long voucherId) {
        var v=db.queryForMap("SELECT pay_value,actual_value,shop_id,min_spend,validity_seconds FROM tb_voucher WHERE id=?",voucherId);
        if(number(v,"pay_value")<0 || number(v,"actual_value")<=0 || number(v,"min_spend")<0
                || number(v,"validity_seconds")<1 || number(v,"validity_seconds")>315360000L)
            throw new BusinessException(ErrorCode.INVALID_PARAM,"优惠券金额或有效期配置不合法");
        db.update("""
                UPDATE tb_voucher_order SET payment_status='PENDING_PAYMENT',pay_amount=?,coupon_face_value=?,
                  coupon_shop_id=?,coupon_min_spend=?,coupon_validity_seconds=?,payment_deadline_ms=? WHERE id=?
                """,number(v,"pay_value"),number(v,"actual_value"),number(v,"shop_id"),number(v,"min_spend"),
                number(v,"validity_seconds"),clock.millis()+config.paymentTimeoutMillis(),orderId);
    }
    @Transactional
    public long consume(long user,long shop,long couponId,long original) {
        // 所有路径按订单 -> 券加锁；新订单插入和锁券失败一起回滚。
        var preliminary=coupon(couponId,false);
        String token=UUID.randomUUID().toString();long now=clock.millis();
        long discount=discount(preliminary,user,shop,original,now);
        long id=JdbcIds.insert(db,"""
                INSERT INTO tb_consumption_order(user_id,shop_id,coupon_id,lock_token,original_amount,
                 discount_amount,pay_amount,payment_deadline_ms,created_at_ms) VALUES (?,?,?,?,?,?,?,?,?)
                """,user,shop,couponId,token,original,discount,original-discount,
                Math.min(now+config.paymentTimeoutMillis(),number(preliminary,"valid_until_ms")),now);
        var c=coupon(couponId,true);
        discount(c,user,shop,original,now);
        int changed=db.update("UPDATE tb_user_coupon SET state='LOCKED',lock_order_id=?,lock_token=?,version=version+1 WHERE id=? AND state='AVAILABLE' AND version=?",
                id,token,couponId,number(c,"version"));
        if(changed!=1) throw new BusinessException(ErrorCode.CONFLICT,"优惠券已被其他订单锁定");
        action(couponId,id,token,"LOCK",now);
        return id;
    }
    private long discount(Map<String,Object> c,long user,long shop,long original,long now) {
        return CommerceRules.discount(number(c,"user_id"),user,number(c,"shop_id"),shop,number(c,"valid_from_ms"),
                number(c,"valid_until_ms"),now,(String)c.get("state"),number(c,"min_spend"),number(c,"face_value"),original);
    }
    @Transactional
    public Receipt simulate(long user,Kind kind,long id,String eventId,long amount,boolean success) {
        if(!config.simulationEnabled()) throw new BusinessException(ErrorCode.NOT_FOUND,"模拟支付未启用");
        if(eventId==null || !eventId.matches("[A-Za-z0-9._:-]{1,64}") || amount<0)
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        var order=order(kind,id,user,true);
        if(amount!=number(order,"pay_amount")) throw new BusinessException(ErrorCode.CONFLICT,"付款金额与订单不一致");
        var prior=db.queryForList("SELECT * FROM tb_payment_event WHERE event_id=?",eventId);
        if(!prior.isEmpty()) {
            var old=prior.getFirst();
            if(!kind.name().equals(old.get("order_kind")) || number(old,"order_id")!=id || number(old,"user_id")!=user
                    || number(old,"amount")!=amount || ((Boolean)old.get("success"))!=success)
                throw new BusinessException(ErrorCode.CONFLICT,"同一支付事件内容冲突");
            return new Receipt(eventId,(String)old.get("outcome"),(String)old.get("order_state"));
        }
        long now=clock.millis();String state=(String)order.get("payment_status");
        String outcome=CommerceRules.paymentOutcome(state,number(order,"payment_deadline_ms"),now,success);
        if("APPLIED".equals(outcome)) {
            if(kind==Kind.PURCHASE) issue(order,now);else redeem(order,now);
            if(db.update("UPDATE "+kind.table+" SET payment_status='PAID' WHERE id=? AND payment_status='PENDING_PAYMENT'",id)!=1)
                throw new BusinessException(ErrorCode.CONFLICT);
            state="PAID";
        } else if("CONFLICT_EXPIRED".equals(outcome)) {
            closeLocked(kind,order,now);state="CANCELLED";
        }
        db.update("INSERT INTO tb_payment_event(event_id,order_kind,order_id,user_id,amount,success,outcome,order_state,created_at_ms) VALUES (?,?,?,?,?,?,?,?,?)",
                eventId,kind.name(),id,user,amount,success,outcome,state,now);
        return new Receipt(eventId,outcome,state);
    }
    private void issue(Map<String,Object> order,long now) {
        db.update("""
                INSERT INTO tb_user_coupon(purchase_order_id,user_id,voucher_id,shop_id,face_value,min_spend,valid_from_ms,valid_until_ms)
                VALUES (?,?,?,?,?,?,?,?)
                """,number(order,"id"),number(order,"user_id"),number(order,"voucher_id"),number(order,"coupon_shop_id"),
                number(order,"coupon_face_value"),number(order,"coupon_min_spend"),now,Math.addExact(now,Math.multiplyExact(number(order,"coupon_validity_seconds"),1000)));
    }
    private void redeem(Map<String,Object> order,long now) {
        long couponId=number(order,"coupon_id");var c=coupon(couponId,true);
        requireLock(order,c);
        if(now<number(c,"valid_from_ms") || now>=number(c,"valid_until_ms")) throw new BusinessException(ErrorCode.CONFLICT,"优惠券已过期");
        db.update("UPDATE tb_user_coupon SET state='USED',version=version+1 WHERE id=? AND state='LOCKED'",couponId);
        action(couponId,number(order,"id"),(String)order.get("lock_token"),"USE",now);
    }
    @Transactional
    public String cancel(long user,Kind kind,long id) {
        var order=order(kind,id,user,true);String state=(String)order.get("payment_status");
        if("CANCELLED".equals(state)) return state;
        if(!"PENDING_PAYMENT".equals(state)) throw new BusinessException(ErrorCode.CONFLICT,"当前订单不允许取消");
        closeLocked(kind,order,clock.millis());return "CANCELLED";
    }
    @Transactional
    public void expire(Kind kind,long id) {
        var rows=db.queryForList("SELECT * FROM "+kind.table+" WHERE id=? FOR UPDATE",id);
        if(rows.isEmpty()) return;
        var order=rows.getFirst();long now=clock.millis();
        if("PENDING_PAYMENT".equals(order.get("payment_status")) && now>=number(order,"payment_deadline_ms")) closeLocked(kind,order,now);
    }
    private void closeLocked(Kind kind,Map<String,Object> order,long now) {
        if(kind==Kind.CONSUMPTION) {
            var c=coupon(number(order,"coupon_id"),true);requireLock(order,c);
            db.update("UPDATE tb_user_coupon SET state=?,lock_order_id=NULL,lock_token=NULL,version=version+1 WHERE id=?",
                    CommerceRules.releasedState(number(c,"valid_until_ms"),now),number(c,"id"));
            action(number(c,"id"),number(order,"id"),(String)order.get("lock_token"),"RELEASE",now);
        }
        db.update("UPDATE "+kind.table+" SET payment_status='CANCELLED' WHERE id=? AND payment_status='PENDING_PAYMENT'",number(order,"id"));
    }
    private void requireLock(Map<String,Object> order,Map<String,Object> coupon) {
        if(!"LOCKED".equals(coupon.get("state")) || coupon.get("lock_order_id")==null
                || number(coupon,"lock_order_id")!=number(order,"id") || !order.get("lock_token").equals(coupon.get("lock_token"))
                || number(coupon,"user_id")!=number(order,"user_id"))
            throw new BusinessException(ErrorCode.CONFLICT,"优惠券锁归属不匹配");
    }
    private void action(long coupon,long order,String token,String action,long now) {
        db.update("INSERT INTO tb_coupon_action(coupon_id,order_id,lock_token,action,created_at_ms) VALUES (?,?,?,?,?)",coupon,order,token,action,now);
    }
    public Map<String,Object> order(Kind kind,long id,long user,boolean lock) {
        var rows=db.queryForList("SELECT * FROM "+kind.table+" WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty() || number(rows.getFirst(),"user_id")!=user) throw new BusinessException(ErrorCode.ORDER_NOT_FOUND);
        return rows.getFirst();
    }
    public Map<String,Object> coupon(long id,boolean lock) {
        var rows=db.queryForList("SELECT * FROM tb_user_coupon WHERE id=?"+(lock?" FOR UPDATE":""),id);
        if(rows.isEmpty()) throw new BusinessException(ErrorCode.NOT_FOUND,"优惠券不存在");return rows.getFirst();
    }
    public List<Map<String,Object>> coupons(long user,long after,int size) {
        if(after<0 || size<1 || size>100) throw new BusinessException(ErrorCode.INVALID_PARAM);
        return db.queryForList("SELECT * FROM tb_user_coupon WHERE user_id=? AND id>? ORDER BY id LIMIT ?",user,after,size);
    }
    static long number(Map<String,Object> row,String key) { return ((Number)row.get(key)).longValue(); }
}
