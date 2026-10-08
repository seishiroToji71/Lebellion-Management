package uz.lebellion.tools

import uz.lebellion.submission.image.DHasher
import java.io.File

/**
 * dHash calibration helper (NOT a test, NOT part of CI). Reads a folder of sample photos and prints the
 * pairwise Hamming-distance matrix, so a human can pick a sensible per-item `dhash_threshold`.
 *
 * Run:  ./gradlew dhashCalibration            (defaults to docs/samples/photos)
 *       ./gradlew dhashCalibration -Pdir=/abs/path/to/photos
 *
 * Drop in "duplicate-ish" pairs (same scene re-shot, re-compressed, slightly cropped) and clearly
 * different scenes; the matrix shows where a threshold cleanly separates the two.
 */
fun main(args: Array<String>) {
    val dir = resolveDir(args.getOrNull(0))
    if (dir == null) {
        System.err.println("No samples folder found. Pass one: ./gradlew dhashCalibration -Pdir=/path/to/photos")
        return
    }
    val files = dir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
        ?.sortedBy { it.name }
        .orEmpty()
    if (files.isEmpty()) {
        println("No .jpg/.jpeg/.png files in ${dir.absolutePath}")
        return
    }

    val hashes = LinkedHashMap<String, Long>()
    for (f in files) {
        try {
            hashes[f.name] = DHasher.hash(f.readBytes())
        } catch (e: Exception) {
            System.err.println("skip ${f.name}: ${e.message}")
        }
    }
    if (hashes.isEmpty()) {
        println("No decodable images in ${dir.absolutePath}")
        return
    }

    val names = hashes.keys.toList()
    val width = (names.maxOf { it.length }).coerceAtLeast(8)
    val header = " ".repeat(width + 2) + names.indices.joinToString(" ") { "%3d".format(it) }
    println("dHash Hamming-distance matrix for ${dir.absolutePath}")
    println(header)
    names.forEachIndexed { i, rowName ->
        val cells = names.map { colName -> "%3d".format(DHasher.hamming(hashes.getValue(rowName), hashes.getValue(colName))) }
        println("%2d %-${width}s %s".format(i, rowName, cells.joinToString(" ")))
    }
}

/** Looks for the given path, then the usual repo-relative spots (task runs from the backend/ dir). */
private fun resolveDir(arg: String?): File? {
    val candidates = buildList {
        if (arg != null) add(File(arg))
        add(File("docs/samples/photos"))
        add(File("../docs/samples/photos"))
    }
    return candidates.firstOrNull { it.isDirectory }
}
