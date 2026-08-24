package com.manasv.batchmigration.parse;

import com.manasv.batchmigration.model.PositionRecord;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Parses one 65-character fixed-width line into a {@link PositionRecord}.
 * Column positions are transcribed from docs/layout.md; this class was
 * written from that spec, not from the Python parser.
 */
@Component
public class FixedWidthPositionParser {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    public static final int RECORD_WIDTH = 65;

    public PositionRecord parse(long recordId, String line) {
        if (line.length() != RECORD_WIDTH) {
            throw new IllegalArgumentException(
                    "record " + recordId + " has width " + line.length() + ", expected " + RECORD_WIDTH);
        }
        String accountId = line.substring(0, 10).trim();
        String isin = line.substring(10, 22).trim();
        BigDecimal quantity = new BigDecimal(line.substring(22, 37).trim());
        BigDecimal price = new BigDecimal(line.substring(37, 52).trim());
        String currency = line.substring(52, 55).trim();
        LocalDate tradeDate = LocalDate.parse(line.substring(55, 63).trim(), DATE_FORMAT);
        int settleCycleDays = Integer.parseInt(line.substring(63, 65).trim());
        return new PositionRecord(recordId, accountId, isin, quantity, price, currency, tradeDate, settleCycleDays);
    }
}
