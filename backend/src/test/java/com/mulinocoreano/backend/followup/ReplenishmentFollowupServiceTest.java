package com.mulinocoreano.backend.followup;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReplenishmentFollowupServiceTest {
    final LocalDate date=LocalDate.of(2026,9,5);
    final OffsetDateTime due=OffsetDateTime.parse("2026-09-05T15:00:00Z");
    @Test void dateBoundaryIsNextMidnightInSeoul() {
        assertThat(ReplenishmentFollowupService.checkAt(date).toInstant()).isEqualTo(due.toInstant());
        var line=new ReplenishmentFollowupRepository.ReceiptLine(1,BigDecimal.TEN,date,Map.of());
        assertThat(ReplenishmentFollowupService.assess(line,due.minusNanos(1)).observation().get("status")).isEqualTo("NOT_DUE");
        assertThat(ReplenishmentFollowupService.assess(line,due).observation().get("status")).isEqualTo("MISSING_RECEIPT");
    }
    @Test void exactPartialHeldBlockedAndMismatchedReceiptsStayUnfulfilled() {
        for(String status:List.of("HOLD","BLOCKED"))assertThat(assess("10",status,true,true).fulfilled()).isFalse();
        assertThat(assess("9.999999","RELEASED",true,true).fulfilled()).isFalse();
        assertThat(assess("10.000001","RELEASED",true,true).fulfilled()).isFalse();
        assertThat(assess("10","RELEASED",false,true).fulfilled()).isFalse();
        assertThat(assess("10","RELEASED",true,false).fulfilled()).isFalse();
        assertThat(assess("10","RELEASED",true,true).fulfilled()).isTrue();
    }
    @Test void splitLotsAreSummedWithoutDoubleCountingInbound() {
        var receipt=new ReplenishmentFollowupRepository.Receipt(1,BigDecimal.TEN,"RELEASED",true,List.of(
            new ReplenishmentFollowupRepository.Lot(1,new BigDecimal("3.123456"),true,new BigDecimal("3.123456")),
            new ReplenishmentFollowupRepository.Lot(2,new BigDecimal("6.876544"),true,new BigDecimal("6.876544"))));
        var assessed=ReplenishmentFollowupService.assess(new ReplenishmentFollowupRepository.ReceiptLine(1,BigDecimal.TEN,date,Map.of(1L,receipt)),due);
        assertThat(assessed.fulfilled()).isTrue();
        assertThat((BigDecimal)assessed.observation().get("usableQuantity")).isEqualByComparingTo("10");
    }
    ReplenishmentFollowupService.Assessment assess(String quantity,String status,boolean identity,boolean lotIdentity) {
        var qty=new BigDecimal(quantity);
        var receipt=new ReplenishmentFollowupRepository.Receipt(1,qty,status,identity,List.of(new ReplenishmentFollowupRepository.Lot(1,qty,lotIdentity,qty)));
        return ReplenishmentFollowupService.assess(new ReplenishmentFollowupRepository.ReceiptLine(1,BigDecimal.TEN,date,Map.of(1L,receipt)),due);
    }
}
