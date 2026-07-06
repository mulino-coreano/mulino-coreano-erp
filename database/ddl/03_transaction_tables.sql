CREATE TABLE purchase_orders (
    purchase_order_id SERIAL PRIMARY KEY,
    supplier_id INT NOT NULL,
    created_by INT NOT NULL,
    order_date DATE NOT NULL,
    expected_date DATE NULL,
    status purchase_order_status NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    tax_invoice_number VARCHAR(50) NULL,
    tax_invoice_date DATE NULL
);

CREATE TABLE purchase_order_items (
    purchase_order_item_id SERIAL PRIMARY KEY,
    purchase_order_id INT NOT NULL,
    raw_material_id INT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(10, 2) NOT NULL,
    received_quantity INT NOT NULL DEFAULT 0
);

CREATE TABLE inbound(
    inbound_id SERIAL PRIMARY KEY,
    raw_material_id INT NOT NULL,
    supplier_id INT NOT NULL,
    warehouse_id INT NOT NULL,
    purchase_order_item_id INT NOT NULL,
    quantity INT NOT NULL,
    inbound_date DATE NOT NULL,
    expiry_date DATE NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE inbound_temperatures_logs(
    inbound_temperature_id SERIAL PRIMARY KEY,
    inbound_id INT NOT NULL,
    temperature DECIMAL(5, 2) NOT NULL,
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    location_note VARCHAR(100) NULL
);

CREATE TABLE production_lots (
    production_lot_id SERIAL PRIMARY KEY,
    product_id INT NOT NULL,
    lot_number VARCHAR(50) NOT NULL UNIQUE,
    production_date DATE NOT NULL,
    expiry_date DATE NOT NULL,
    quantity INT NOT NULL,  
    status production_lot_status NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);


CREATE TABLE recalls(
    recall_id SERIAL PRIMARY KEY,
    lot_id INT NULL,
    raw_lot_id INT NULL,
    recall_date DATE NOT NULL,
    reason TEXT NULL,
    status recall_status NOT NULL DEFAULT 'OPEN',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE warehouse_temperatures_logs(
    warehouse_temperature_id SERIAL PRIMARY KEY,
    warehouse_id INT NOT NULL,
    temperature DECIMAL(5, 2) NOT NULL,
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE outbound(
    outbound_id SERIAL PRIMARY KEY,
    product_id INT NOT NULL,
    warehouse_id INT NOT NULL,
    order_id INT NOT NULL,
    quantity INT NOT NULL,
    outbound_date DATE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE outbound_lots(
    outbound_lot_id SERIAL PRIMARY KEY,
    outbound_id INT NOT NULL,
    lot_quantity INT NOT NULL,
    lot_id INT NOT NULL
);

CREATE TABLE orders(
    order_id SERIAL PRIMARY KEY,
    customer_id INT NOT NULL,
    created_by INT NOT NULL,
    order_date DATE NOT NULL,
    expected_delivery_date DATE NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    status order_status NOT NULL DEFAULT 'PENDING',
    tax_invoice_number VARCHAR(50) NULL,
    tax_invoice_date DATE NULL
);

CREATE TABLE order_items(
    orders_item_id SERIAL PRIMARY KEY,
    order_id INT NOT NULL,
    product_id INT NOT NULL,
    quantity INT NOT NULL,
    unit_price DECIMAL(10, 2) NOT NULL
);

CREATE TABLE raw_material_lots(
    raw_material_lot_id SERIAL PRIMARY KEY,
    raw_material_id INT NOT NULL,
    inbound_id INT NOT NULL,
    supplier_lot_number VARCHAR(50) NULL,
    lot_number VARCHAR(50) NOT NULL UNIQUE,
    quantity INT NOT NULL,
    remaining_quantity INT NOT NULL,
    production_date DATE NULL,
    expiry_date DATE NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE production_records(
    production_record_id SERIAL PRIMARY KEY,
    lot_id INT NOT NULL,
    warehouse_id INT NOT NULL,
    operator_id INT NOT NULL,
    process_type production_record_process_type NOT NULL,
    start_time TIMESTAMP NOT NULL,
    end_time TIMESTAMP NULL,
    temperature DECIMAL(5, 2) NULL,
    note TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE stock(
    stock_id SERIAL PRIMARY KEY,
    product_id INT NOT NULL,
    warehouse_id INT NOT NULL,
    quantity INT NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE production_ingredients(
    production_ingredient_id SERIAL PRIMARY KEY,
    production_record_id INT NOT NULL,
    raw_material_lot_id INT NOT NULL,
    quantity_used INT NOT NULL
);