CREATE TABLE raw_material_allergens (
    raw_material_allergen_id SERIAL PRIMARY KEY,
    raw_material_id INT NOT NULL,
    allergen_id INT NOT NULL,
    is_trace BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE supplier_certifications (
    supplier_certification_id SERIAL PRIMARY KEY,
    supplier_id INT NOT NULL,
    cert_type supplier_certification_cert_type NOT NULL,
    cert_number VARCHAR(100) NOT NULL,
    issued_by VARCHAR(100) NOT NULL,
    issue_date DATE NOT NULL,
    expiry_date DATE NOT NULL,
    file_url VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
