package com.manasv.batchmigration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.nio.file.Path;

/**
 * Spring Boot reimplementation of the legacy fixed-width EOD position
 * batch. Run as:
 *
 *   java -jar batch-migration-differ.jar &lt;input-file&gt; &lt;output-csv&gt;
 *
 * On startup, Spring wires {@link BatchProcessorService} and its
 * dependencies, the CommandLineRunner below drives one batch pass over the
 * given input file, and the process exits. This is the same "batch service"
 * shape used elsewhere in this codebase for scheduled EOD jobs: a real
 * Spring application context, not just a standalone script, so the same
 * beans (parser, valuation service, writer) are unit-testable in isolation
 * and could be re-wired behind a scheduler or a REST trigger without
 * touching the business logic.
 */
@SpringBootApplication
public class BatchMigrationApplication implements CommandLineRunner {

    private final BatchProcessorService batchProcessorService;

    @Autowired
    public BatchMigrationApplication(BatchProcessorService batchProcessorService) {
        this.batchProcessorService = batchProcessorService;
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(BatchMigrationApplication.class);
        app.setLogStartupInfo(false);
        System.exit(SpringApplication.exit(app.run(args)));
    }

    @Override
    public void run(String... args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: java -jar batch-migration-differ.jar <input-file> <output-csv>");
            throw new IllegalArgumentException("expected 2 arguments, got " + args.length);
        }
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        int count = batchProcessorService.run(input, output);
        System.out.println("BatchMigrationApplication: processed " + count + " record(s) -> " + output);
    }
}
