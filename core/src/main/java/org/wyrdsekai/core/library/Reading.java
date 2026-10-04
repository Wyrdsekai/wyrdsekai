package org.wyrdsekai.core.library;

import org.wyrdsekai.core.library.ReadingLedger.Bookmark;
import org.wyrdsekai.core.search.WyrdLuceneStore.BookRef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The rules of reading a book through, pure: which book her words mean, what a page says, what comes next. */
public final class Reading {

    private Reading() {}

    /** A part is served whole; a part longer than this is cut, and said to be. */
    public static final int PART_CHARS = 4_200;

    private static final Set<String> NOT_WORDS = Set.of("the", "and", "for", "with", "that", "this", "book", "books",
        "novel", "read", "reading", "finish", "finishing", "continue", "keep", "next", "chapter", "part", "epub", "pdf",
        "one", "some", "about", "from", "please", "want", "would", "like", "again", "more", "rest", "all", "whole");

    /** The words of a book's name in a question: three letters and up, the words of asking left out. */
    public static List<String> words(String asked) {
        var out = new LinkedHashSet<String>();
        if (asked == null) return List.of();
        for (var w : asked.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 3 && !NOT_WORDS.contains(w)) out.add(w);
        }
        return new ArrayList<>(out);
    }

    /** Whether a book's name holds every word asked. */
    public static boolean matches(String book, List<String> words) {
        if (book == null || words.isEmpty()) return false;
        var low = book.toLowerCase(Locale.ROOT);
        for (var w : words) if (!low.contains(w)) return false;
        return true;
    }

    /** Of her own books, the ones the words mean. */
    public static List<Bookmark> hersMatching(List<Bookmark> hers, List<String> words) {
        var out = new ArrayList<Bookmark>();
        for (var b : hers) if (matches(b.book(), words)) out.add(b);
        return out;
    }

    /** The page as she is shown it: the book, where she is, the text, and what to do next. */
    public static String page(Bookmark after, String text, boolean cut) {
        var sb = new StringBuilder();
        sb.append(after.title()).append(" — part ").append(after.part()).append(" of ").append(after.total()).append(":\n\n");
        sb.append(text == null ? "" : text.strip());
        if (cut) sb.append("\n[…this part was cut at ").append(PART_CHARS).append(" characters]");
        sb.append("\n\n");
        if (after.finished()) {
            sb.append("You have finished ").append(after.title()).append(": all ").append(after.total()).append(" parts.");
        } else {
            sb.append("(That is where you are. read_book again reads part ").append(after.next()).append(" of ")
              .append(after.total()).append(". read_book with \"book\": \"<name>\" opens another.)");
        }
        return sb.toString();
    }

    /** One line per book on her shelf. */
    public static String shelf(List<Bookmark> hers) {
        var sb = new StringBuilder();
        for (var b : hers) {
            sb.append("\n  ").append(b.title()).append(b.finished() ? " — finished" : " — at part " + b.part() + " of " + b.total());
        }
        return sb.toString();
    }

    /** What the shelf offers next: the ones she began and left, then the ones named here. */
    public static String nextOffers(List<Bookmark> hers, List<BookRef> others) {
        var sb = new StringBuilder();
        int n = 0;
        for (var b : hers) {
            if (b.finished() || n >= 5) continue;
            sb.append("\n  ").append(b.title()).append(" — you are at part ").append(b.part()).append(" of ").append(b.total());
            n++;
        }
        for (var o : others) {
            if (n >= 8) break;
            boolean hers2 = hers.stream().anyMatch(b -> b.book().equals(o.group()));
            if (hers2) continue;
            sb.append("\n  ").append(ReadingLedger.titleOf(o.group())).append(" — ").append(o.parts()).append(" parts, not yet begun");
            n++;
        }
        return sb.toString();
    }
}
