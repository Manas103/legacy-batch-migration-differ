package com.manasv.batchmigration.io;

import com.manasv.batchmigration.model.PositionResult;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes {@link PositionResult}s to CSV in the same shape the legacy Python side writes. */
@Component
public class PositionResultWriter {

    public void write(Path outputPath, List<PositionResult> results) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(outputPath)) {
            writer.write("record_id,account_id,isin,currency,market_value,settle_date");
            writer.newLine();
            for (PositionResult r : results) {
                writer.write(String.join(",",
                        Long.toString(r.recordId()),
                        r.accountId(),
                        r.isin(),
                        r.currency(),
                        r.marketValue().toPlainString(),
                        r.settleDate().toString()));
                writer.newLine();
            }
        }
    }
}
