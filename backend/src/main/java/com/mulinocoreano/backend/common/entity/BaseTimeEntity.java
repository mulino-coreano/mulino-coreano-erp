package com.mulinocoreano.backend.domain.supplier.entity;

import com.mulinocoreano.backend.common.entity.BaseTimeEntity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "suppliers")
public class Supplier extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "supplier_id")
    private Long supplierId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "country", nullable = false, length = 50)
    private String country;

    @Column(name = "contact_name", length = 50)
    private String contactName;

    @Column(name = "contact_email", length = 100)
    private String contactEmail;

    @Column(name = "contact_phone", length = 20)
    private String contactPhone;

    @Column(name = "address_line", length = 255)
    private String addressLine;

    @Column(name = "city", length = 100)
    private String city;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "payment_terms", nullable = false, columnDefinition = "payment_terms")
    @Enumerated(EnumType.STRING)
    private PaymentTerms paymentTerms;

    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    private String currency = "KRW";

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    protected Supplier() {
    }

    // getter / 생성자 / 정적 팩토리는 B단계에서 컨벤션 확정 후 추가
}