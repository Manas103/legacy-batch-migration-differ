package com.manasv.batchmigration;

import com.manasv.batchmigration.calc.PositionValuationService;
import com.manasv.batchmigration.io.PositionResultWriter;
import com.manasv.batchmigration.model.PositionRecord;
import com.manasv.batchmigration.model.PositionResult;
import com.manasv.batchmigration.parse.FixedWidthPositionParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates one end-of-day batch run: parse the fixed-width input file,
 * evaluate every record, write the CSV of results. This is the Spring Boot
 * service that reimplements the legacy batch; {@link BatchMigrationApplication}
 * is the thin CommandLineRunner wrapper that lets it be invoked from the
 * command line the same way the legacy script is.
 */
@Service
public class BatchProcessorService {

    private final FixedWidthPositionParser parser;
    private final PositionValuationService valuationService;
    private final PositionResultWriter writer;

    @Autowired
    public BatchProcessorService(FixedWidthPositionParser parser, PositionValuationService valuationService,
                                  PositionResultWriter writer) {
        this.parser = parser;
        this.valuationService = valuationService;
        this.writer = writer;
    }

    public int run(Path inputPath, Path outputPath) throws IOException {
        List<String> lines = Files.readAllLines(inputPath);
        List<PositionResult> results = new ArrayList<>(lines.size());
        long recordId = 1;
        for (String line : lines) {
            if (line.isEmpty()) {
                continue;
            }
            PositionRecord record = parser.parse(recordId, line);
            results.add(valuationService.evaluate(record));
            recordId++;
        }
        writer.write(outputPath, results);
        return results.size();
    }
}
