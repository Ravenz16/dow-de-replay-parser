package test;

import com.dow.replay.analyzer.CompressionScanner;
import com.dow.replay.analyzer.EmbeddedChunkyFinder;
import com.dow.replay.analyzer.GameplayPayloadAnalyzer;
import com.dow.replay.chunk.*;
import com.dow.replay.parser.BinaryReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Main {

    public static void main(String[] args)
            throws Exception {

        byte[] data =
                Files.readAllBytes(
                        Path.of(args[0])
                );

        RecursiveRelicChunkParser parser =
                new RecursiveRelicChunkParser(
                        new BinaryReader(data)
                );

        RelicChunkNode root =
                parser.parse(data);

        System.out.println();
        System.out.println(
                "========== TREE =========="
        );

        ChunkTreePrinter.print(root);

        System.out.println();
        System.out.println(
                "========== GPLY =========="
        );

        List<RelicChunkNode> gply =
                ChunkSearch.findById(
                        root,
                        "GPLY"
                );

        for (RelicChunkNode n : gply) {

            System.out.println(n);
        }

        System.out.println();
        System.out.println(
                "========== DATA =========="
        );

        List<RelicChunkNode> dataChunks =
                ChunkSearch.findById(
                        root,
                        "DATA"
                );

        for (RelicChunkNode n : dataChunks) {

            GameplayPayloadAnalyzer
                    .analyze(n);
        }

        System.out.println();
        System.out.println(
                "========== EMBEDDED CHUNKY =========="
        );

        EmbeddedChunkyFinder.search(root);

        System.out.println();
        System.out.println(
                "========== COMPRESSION =========="
        );

        CompressionScanner.scan(root);
    }
}