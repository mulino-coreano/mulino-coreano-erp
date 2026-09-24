-- 재보충 계산 전용 fixture. 기준일 2026-09-05 (Asia/Seoul).
-- Flyway V19 또는 독립 DDL 00~11을 적용한 비어 있는 폐기용 DB에만 실행한다.
-- DO 하나가 전체 트랜잭션이며 중간 실패는 전부 롤백된다. 반복 실행은 거부한다.
-- 로그인 가능한 신원·비밀번호·외부 인증 연결을 생성하지 않는다.
DO $$
DECLARE
    demo_user BIGINT;
    plant BIGINT;
    fast_supplier BIGINT;
    bulk_supplier BIGINT;
    customer BIGINT;
    amr BIGINT;
    bsc BIGINT;
    dough BIGINT;
    flour BIGINT;
    sugar BIGINT;
    packaging BIGINT;
    received_po BIGINT;
    open_po BIGINT;
    item_id BIGINT;
    inbound_ref BIGINT;
    flour_lot BIGINT;
    sugar_lot BIGINT;
    packaging_lot BIGINT;
    consumed_dough BIGINT;
    current_dough BIGINT;
    historic_amr BIGINT;
    historic_bsc BIGINT;
    current_amr BIGINT;
    current_bsc BIGINT;
    quarantine_amr BIGINT;
    expired_amr BIGINT;
    record_ref BIGINT;
    bom_ref BIGINT;
    order_ref BIGINT;
    outbound_ref BIGINT;
    item RECORD;
    day_offset INT;
BEGIN
    IF EXISTS (SELECT 1 FROM users) OR EXISTS (SELECT 1 FROM products)
       OR EXISTS (SELECT 1 FROM suppliers) OR EXISTS (SELECT 1 FROM raw_materials)
       OR EXISTS (SELECT 1 FROM warehouses) OR EXISTS (SELECT 1 FROM customers)
       OR EXISTS (SELECT 1 FROM orders) OR EXISTS (SELECT 1 FROM purchase_orders)
       OR EXISTS (SELECT 1 FROM production_lots)
       OR EXISTS (SELECT 1 FROM cases) THEN
        RAISE EXCEPTION 'replenishment_demo requires an empty disposable ERP database';
    END IF;

    INSERT INTO users(name,email,password,role,is_active)
        VALUES ('DEMO 이력 담당자','replenishment-demo@example.invalid',NULL,'OPERATOR',false)
        RETURNING user_id INTO demo_user;
    INSERT INTO warehouses(name,location,type,plant_id)
        VALUES ('DEMO 생산 거점','DEMO 대한민국','AMBIENT','DEMO-KR-01') RETURNING warehouse_id INTO plant;
    INSERT INTO suppliers(name,country,contact_name)
        VALUES ('DEMO 신속 공급','KR','DEMO 담당자') RETURNING supplier_id INTO fast_supplier;
    INSERT INTO suppliers(name,country,contact_name)
        VALUES ('DEMO 대량 공급','KR','DEMO 담당자') RETURNING supplier_id INTO bulk_supplier;
    INSERT INTO customers(name,address) VALUES ('DEMO 국내 고객','DEMO 대한민국') RETURNING customer_id INTO customer;
    INSERT INTO products(name,sku,unit,expiry_days,product_type,attributes) VALUES
        ('DEMO AMR 완제품','DEMO-AMR','CASE',180,'FINISHED_GOODS','{"fixture":"DEMO"}') RETURNING product_id INTO amr;
    INSERT INTO products(name,sku,unit,expiry_days,product_type,attributes) VALUES
        ('DEMO BSC 완제품','DEMO-BSC','CASE',180,'FINISHED_GOODS','{"fixture":"DEMO"}') RETURNING product_id INTO bsc;
    INSERT INTO products(name,sku,unit,expiry_days,product_type,attributes) VALUES
        ('DEMO 공용 반죽','DEMO-DOUGH','KG',180,'SEMI_FINISHED','{"fixture":"DEMO"}') RETURNING product_id INTO dough;
    INSERT INTO raw_materials(name,unit,supplier_id,attributes)
        VALUES ('DEMO 밀가루','KG',fast_supplier,'{"fixture":"DEMO"}') RETURNING raw_material_id INTO flour;
    INSERT INTO raw_materials(name,unit,supplier_id,attributes)
        VALUES ('DEMO 설탕','KG',fast_supplier,'{"fixture":"DEMO"}') RETURNING raw_material_id INTO sugar;
    INSERT INTO raw_materials(name,unit,material_type,supplier_id,attributes)
        VALUES ('DEMO 포장재','EA','PACKAGING',fast_supplier,'{"fixture":"DEMO"}') RETURNING raw_material_id INTO packaging;
    INSERT INTO planning_policies(warehouse_id,history_start_date) VALUES (plant,'2026-07-11');

    INSERT INTO supplier_certifications(supplier_id,cert_type,cert_number,issued_by,issue_date,expiry_date)
    SELECT supplier_id,cert_type::supplier_certification_cert_type,
           'DEMO-' || supplier_id || '-' || cert_type,'DEMO 인증기관','2026-01-01','2027-01-01'
    FROM (VALUES (fast_supplier),(bulk_supplier)) suppliers(supplier_id)
    CROSS JOIN (VALUES ('HACCP'),('GMP'),('TRACEABILITY')) certs(cert_type);
    -- 단가는 구매단위별 KRW. 소량 구매는 신속 공급의 낮은 MOQ가 더 저렴하다.
    INSERT INTO supplier_material_terms(raw_material_id,supplier_id,purchase_unit,
        base_quantity_per_purchase_unit,unit_price,minimum_order_quantity,order_multiple,
        lead_time_days,valid_from,valid_to,required_cert_types)
    VALUES
        (flour,fast_supplier,'KG',1,1200,5,1,2,'2026-01-01','2026-12-31',ARRAY['HACCP','TRACEABILITY']::supplier_certification_cert_type[]),
        (flour,bulk_supplier,'KG',1,1000,10,5,4,'2026-01-01','2026-12-31',ARRAY['HACCP','TRACEABILITY']::supplier_certification_cert_type[]),
        (sugar,fast_supplier,'KG',1,1500,1,1,2,'2026-01-01','2026-12-31',ARRAY['HACCP']::supplier_certification_cert_type[]),
        (sugar,bulk_supplier,'KG',1,1000,10,5,4,'2026-01-01','2026-12-31',ARRAY['HACCP']::supplier_certification_cert_type[]),
        (packaging,fast_supplier,'EA',1,50,10,10,2,'2026-01-01','2026-12-31',ARRAY['GMP']::supplier_certification_cert_type[]),
        (packaging,bulk_supplier,'EA',1,40,100,100,4,'2026-01-01','2026-12-31',ARRAY['GMP']::supplier_certification_cert_type[]);

    INSERT INTO bom_versions(product_id,version,batch_output_quantity,production_lead_days,valid_from)
        VALUES (amr,1,10,1,'2026-01-01') RETURNING bom_version_id INTO bom_ref;
    INSERT INTO bom_components(bom_version_id,child_product_id,quantity_per_batch) VALUES (bom_ref,dough,2);
    INSERT INTO bom_components(bom_version_id,raw_material_id,quantity_per_batch) VALUES (bom_ref,packaging,10);
    INSERT INTO bom_versions(product_id,version,batch_output_quantity,production_lead_days,valid_from)
        VALUES (bsc,1,10,1,'2026-01-01') RETURNING bom_version_id INTO bom_ref;
    INSERT INTO bom_components(bom_version_id,child_product_id,quantity_per_batch) VALUES (bom_ref,dough,3);
    INSERT INTO bom_components(bom_version_id,raw_material_id,quantity_per_batch) VALUES (bom_ref,packaging,10);
    INSERT INTO bom_versions(product_id,version,batch_output_quantity,production_lead_days,valid_from)
        VALUES (dough,1,5,2,'2026-01-01') RETURNING bom_version_id INTO bom_ref;
    INSERT INTO bom_components(bom_version_id,raw_material_id,quantity_per_batch)
        VALUES (bom_ref,flour,3),(bom_ref,sugar,2);

    -- 과거 생산까지 추적 가능한 입고. 남은 실재고는 밀가루3KG/설탕1KG/포장재20EA.
    INSERT INTO purchase_orders(supplier_id,created_by,order_date,expected_delivery_date,status)
        VALUES (fast_supplier,demo_user,'2026-05-29','2026-05-31','COMPLETED') RETURNING purchase_order_id INTO received_po;
    FOR item IN SELECT * FROM (VALUES
        (flour,36.42::NUMERIC,3::NUMERIC,1200::NUMERIC,'FLOUR'),
        (sugar,23.28::NUMERIC,1::NUMERIC,1500::NUMERIC,'SUGAR'),
        (packaging,243::NUMERIC,20::NUMERIC,50::NUMERIC,'PACK'))
        x(material_id,quantity,remaining,price,tag)
    LOOP
        INSERT INTO purchase_order_items(purchase_order_id,raw_material_id,quantity,received_quantity,unit_price)
            VALUES (received_po,item.material_id,item.quantity,item.quantity,item.price) RETURNING purchase_order_item_id INTO item_id;
        INSERT INTO inbound(raw_material_id,supplier_id,warehouse_id,purchase_order_item_id,quantity,
            inbound_date,expiry_date,status,status_reason,status_decided_by,status_decided_at)
            VALUES (item.material_id,fast_supplier,plant,item_id,item.quantity,'2026-05-31','2026-12-31',
                'RELEASED','DEMO 과거 품질 승인',demo_user,'2026-05-31 09:00:00') RETURNING inbound_id INTO inbound_ref;
        INSERT INTO raw_material_lots(raw_material_id,inbound_id,supplier_lot_number,lot_number,quantity,remaining_quantity,production_date,expiry_date)
            VALUES (item.material_id,inbound_ref,'DEMO-SUP-' || item.tag,'DEMO-RM-' || item.tag,
                item.quantity,item.remaining,'2026-05-20','2026-12-31') RETURNING raw_material_lot_id INTO item_id;
        CASE item.tag WHEN 'FLOUR' THEN flour_lot := item_id;
            WHEN 'SUGAR' THEN sugar_lot := item_id; WHEN 'PACK' THEN packaging_lot := item_id; END CASE;
    END LOOP;
    INSERT INTO purchase_order_items(purchase_order_id,raw_material_id,quantity,received_quantity,unit_price)
        VALUES (received_po,flour,2,2,1200) RETURNING purchase_order_item_id INTO item_id;
    INSERT INTO inbound(raw_material_id,supplier_id,warehouse_id,purchase_order_item_id,quantity,inbound_date,expiry_date,status)
        VALUES (flour,fast_supplier,plant,item_id,2,'2026-09-04','2026-12-31','HOLD') RETURNING inbound_id INTO inbound_ref;
    INSERT INTO raw_material_lots(raw_material_id,inbound_id,supplier_lot_number,lot_number,quantity,remaining_quantity,production_date,expiry_date)
        VALUES (flour,inbound_ref,'DEMO-SUP-HOLD','DEMO-RM-FLOUR-HOLD',2,2,'2026-09-01','2026-12-31');
    INSERT INTO purchase_orders(supplier_id,created_by,order_date,expected_delivery_date,status)
        VALUES (fast_supplier,demo_user,'2026-09-04','2026-09-07','ORDERED') RETURNING purchase_order_id INTO open_po;
    INSERT INTO purchase_order_items(purchase_order_id,raw_material_id,quantity,received_quantity,unit_price)
        VALUES (open_po,flour,2,0,1200);

    -- 과거 반죽 51.7KG은 완제품 생산에 전부 투입되었고 현재 반죽 4KG만 남는다.
    INSERT INTO production_lots(product_id,warehouse_id,lot_number,production_date,expiry_date,quantity,status)
        VALUES (dough,plant,'DEMO-DOUGH-CONSUMED','2026-06-01','2026-12-31',51.7,'CONSUMED') RETURNING production_lot_id INTO consumed_dough;
    INSERT INTO production_lots(product_id,warehouse_id,lot_number,production_date,expiry_date,quantity)
        VALUES (dough,plant,'DEMO-DOUGH-CURRENT','2026-09-01','2026-12-31',4) RETURNING production_lot_id INTO current_dough;
    FOR item IN SELECT * FROM (VALUES (consumed_dough,31.02::NUMERIC,20.68::NUMERIC,'2026-06-01'::DATE),
            (current_dough,2.4::NUMERIC,1.6::NUMERIC,'2026-09-01'::DATE)) x(lot_id,flour_used,sugar_used,produced)
    LOOP
        INSERT INTO production_records(lot_id,warehouse_id,operator_id,process_type,start_time,end_time,note)
            VALUES (item.lot_id,plant,demo_user,'MIX',item.produced + TIME '09:00',item.produced + TIME '10:00','DEMO 반죽 생산')
            RETURNING production_record_id INTO record_ref;
        INSERT INTO production_ingredients(production_record_id,raw_material_lot_id,quantity_used)
            VALUES (record_ref,flour_lot,item.flour_used),(record_ref,sugar_lot,item.sugar_used);
    END LOOP;
    FOR item IN SELECT * FROM (VALUES
        (amr,'DEMO-AMR-HISTORY',112::NUMERIC,'CONSUMED','2026-07-01'::DATE,'2026-12-31'::DATE,22.4::NUMERIC),
        (bsc,'DEMO-BSC-HISTORY',56::NUMERIC,'CONSUMED','2026-07-01'::DATE,'2026-12-31'::DATE,16.8::NUMERIC),
        (amr,'DEMO-AMR-CURRENT',30::NUMERIC,'ACTIVE','2026-09-01'::DATE,'2026-12-31'::DATE,6::NUMERIC),
        (bsc,'DEMO-BSC-CURRENT',15::NUMERIC,'ACTIVE','2026-09-01'::DATE,'2026-12-31'::DATE,4.5::NUMERIC),
        (amr,'DEMO-AMR-QUARANTINE',5::NUMERIC,'QUARANTINE','2026-09-01'::DATE,'2026-12-31'::DATE,1::NUMERIC),
        (amr,'DEMO-AMR-EXPIRED',5::NUMERIC,'ACTIVE','2026-07-01'::DATE,'2026-09-04'::DATE,1::NUMERIC))
        x(product_id,tag,quantity,status,produced,expiry,dough_used)
    LOOP
        INSERT INTO production_lots(product_id,warehouse_id,lot_number,production_date,expiry_date,quantity,status)
            VALUES (item.product_id,plant,item.tag,item.produced,item.expiry,item.quantity,item.status::production_lot_status)
            RETURNING production_lot_id INTO item_id;
        IF item.tag='DEMO-AMR-HISTORY' THEN historic_amr := item_id; END IF;
        IF item.tag='DEMO-BSC-HISTORY' THEN historic_bsc := item_id; END IF;
        INSERT INTO production_records(lot_id,warehouse_id,operator_id,process_type,start_time,end_time,note)
            VALUES (item_id,plant,demo_user,'PACK',item.produced + TIME '11:00',item.produced + TIME '12:00','DEMO 완제품 생산')
            RETURNING production_record_id INTO record_ref;
        INSERT INTO production_product_inputs(production_record_id,source_production_lot_id,quantity_used)
            VALUES (record_ref,consumed_dough,item.dough_used);
        INSERT INTO production_ingredients(production_record_id,raw_material_lot_id,quantity_used)
            VALUES (record_ref,packaging_lot,item.quantity);
    END LOOP;
    INSERT INTO stock(product_id,warehouse_id,quantity) VALUES (amr,plant,40),(bsc,plant,15),(dough,plant,4);

    -- 2026-07-11 ~ 09-04: 매일 1개 주문에 AMR 2 CASE + BSC 1 CASE를 모두 출고.
    FOR day_offset IN 1..56 LOOP
        INSERT INTO orders(customer_id,created_by,order_date,expected_delivery_date,status)
            VALUES (customer,demo_user,DATE '2026-09-05'-day_offset,DATE '2026-09-05'-day_offset,'SHIPPED')
            RETURNING order_id INTO order_ref;
        INSERT INTO order_items(order_id,product_id,quantity,unit_price)
            VALUES (order_ref,amr,2,4000),(order_ref,bsc,1,3000);
        INSERT INTO outbound(product_id,warehouse_id,order_id,quantity,outbound_date)
            VALUES (amr,plant,order_ref,2,DATE '2026-09-05'-day_offset) RETURNING outbound_id INTO outbound_ref;
        INSERT INTO outbound_lots(outbound_id,lot_id,lot_quantity) VALUES (outbound_ref,historic_amr,2);
        INSERT INTO outbound(product_id,warehouse_id,order_id,quantity,outbound_date)
            VALUES (bsc,plant,order_ref,1,DATE '2026-09-05'-day_offset) RETURNING outbound_id INTO outbound_ref;
        INSERT INTO outbound_lots(outbound_id,lot_id,lot_quantity) VALUES (outbound_ref,historic_bsc,1);
    END LOOP;
END;
$$;
