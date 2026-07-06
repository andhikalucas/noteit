using System.Text.RegularExpressions;
using VoiceFlow.Core.Models;

namespace VoiceFlow.Core.Services;

/// <summary>
/// The "auto-edits" pipeline: takes raw Whisper output and produces polished text.
/// Order: dictionary fixes → "press enter" detection → snippet expansion →
/// spoken layout commands → filler cleanup per level → whitespace/capitalization tidy.
/// English-specific rules are skipped for non-English dictations.
/// </summary>
public static class TextFormatter
{
    private static readonly RegexOptions Opts = RegexOptions.IgnoreCase | RegexOptions.CultureInvariant;

    // Pure vocal fillers — safe to remove at Light and above.
    private static readonly Regex LightFillers = new(
        @"(?<=^|[\s,.;:!?])(?:um+|uh+|uhm+|umm+|erm+|ehm+|hmm+|mhm+)(?=$|[\s,.;:!?])[,.]?\s*",
        Opts | RegexOptions.Compiled);

    // Parenthetical fillers — removed at Medium and above when set off by commas.
    private static readonly Regex MediumParenthetical = new(
        @",\s*(?:you know|i mean|like)\s*,", Opts | RegexOptions.Compiled);

    private static readonly Regex MediumLeadIn = new(
        @"^(?:so|well|okay|ok|right|anyway)[,]\s+", Opts | RegexOptions.Compiled);

    // "the the", "I I" → single word. Applied at Medium+.
    private static readonly Regex RepeatedWord = new(
        @"\b(\w+)(?:\s+\1\b)+", Opts | RegexOptions.Compiled);

    // Hedges removed at High.
    private static readonly Regex HighHedges = new(
        @"(?<=^|[\s,])(?:basically|literally|honestly|obviously)[,]?\s+", Opts | RegexOptions.Compiled);

    private static readonly Regex HighLikeComma = new(@"(?<=[\s])like,\s+", Opts | RegexOptions.Compiled);

    // Optional "and" prefix only — "then" stays, since "see you then press enter"
    // means the sentence ends with "then".
    private static readonly Regex PressEnter = new(
        @"[\s,.]*(?:\band\s+)?\bpress(?:es|ed)?\s+enter\b[\s.!?]*$", Opts | RegexOptions.Compiled);

    private static readonly Regex NewParagraph = new(
        @"[\s,.]*\bnew\s+paragraph\b[\s,.]*", Opts | RegexOptions.Compiled);

    private static readonly Regex NewLine = new(
        @"[\s,.]*\bnew\s+line\b[\s,.]*", Opts | RegexOptions.Compiled);

    private static readonly Regex MultiSpace = new(@"[ \t]{2,}", RegexOptions.Compiled);
    private static readonly Regex SpaceBeforePunct = new(@"[ \t]+([,.;:!?])", RegexOptions.Compiled);
    // Comma-family always gets a following space; periods only between a lowercase word and
    // an uppercase letter, so URLs ("example.com") and acronyms ("U.S.") survive intact.
    private static readonly Regex NoSpaceAfterComma = new(@"([,;:!?])(?=\p{L})", RegexOptions.Compiled);
    private static readonly Regex NoSpaceAfterPeriod = new(@"(?<=\p{Ll}\p{Ll})\.(?=\p{Lu})", RegexOptions.Compiled);
    private static readonly Regex DupPunct = new(@"([,.;:!?])\1+", RegexOptions.Compiled);
    private static readonly Regex LonelyI = new(@"\bi\b", RegexOptions.Compiled);

    // Dictionary/snippet patterns are user data evaluated on every dictation — cache the
    // compiled regexes instead of recompiling per entry per utterance.
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<string, Regex> UserRegexCache = new();

    private static Regex CachedUserRegex(string pattern)
    {
        if (UserRegexCache.Count > 512) UserRegexCache.Clear(); // crude bound; rebuilt on demand
        return UserRegexCache.GetOrAdd(pattern, p => new Regex(p, Opts | RegexOptions.Compiled));
    }

    public static FormatResult Format(
        string raw,
        CleanupLevel level,
        IReadOnlyList<DictionaryEntry>? dictionary = null,
        IReadOnlyList<Snippet>? snippets = null,
        string language = "en",
        List<long>? matchedDictionaryIds = null,
        List<long>? matchedSnippetIds = null)
    {
        var result = new FormatResult();
        var text = (raw ?? "").Trim();
        if (text.Length == 0) return result;

        bool english = language is "en" or "auto" or "" ||
                       language.StartsWith("en", StringComparison.OrdinalIgnoreCase);

        // 1. Dictionary: fix misheard forms and normalize casing of known phrases.
        if (dictionary is { Count: > 0 })
            text = ApplyDictionary(text, dictionary, matchedDictionaryIds);

        // 2. Trailing "press enter" is a command, not content — at every cleanup level.
        if (english)
        {
            var match = PressEnter.Match(text);
            if (match.Success && match.Index > 0)
            {
                text = PressEnter.Replace(text, "");
                result.SendEnter = true;
            }
        }

        // 3. Whole-utterance snippet: the cue IS the dictation — return stored content
        //    verbatim so emails, URLs and formatted blocks aren't mangled by cleanup.
        if (snippets is { Count: > 0 })
        {
            var whole = MatchWholeSnippet(text, snippets);
            if (whole != null)
            {
                matchedSnippetIds?.Add(whole.Id);
                result.Text = whole.Content;
                return result;
            }
            text = ApplyInlineSnippets(text, snippets, matchedSnippetIds);
        }

        if (level >= CleanupLevel.Light)
        {
            // 4. Spoken layout commands.
            if (english)
            {
                text = NewParagraph.Replace(text, "\n\n");
                text = NewLine.Replace(text, "\n");
            }

            // 5. Filler removal, scaled by level. English-only heuristics.
            if (english)
            {
                text = LightFillers.Replace(text, "");
                if (level >= CleanupLevel.Medium)
                {
                    text = MediumParenthetical.Replace(text, ",");
                    text = MediumLeadIn.Replace(text, "");
                    text = RepeatedWord.Replace(text, "$1");
                }
                if (level >= CleanupLevel.High)
                {
                    text = HighHedges.Replace(text, "");
                    text = HighLikeComma.Replace(text, "");
                }
            }

            // 6. Whitespace and punctuation tidy.
            text = SpaceBeforePunct.Replace(text, "$1");
            text = DupPunct.Replace(text, "$1");
            text = NoSpaceAfterComma.Replace(text, "$1 ");
            text = NoSpaceAfterPeriod.Replace(text, ". ");
            text = MultiSpace.Replace(text, " ");
            text = Regex.Replace(text, @" *\n *", "\n");
            text = text.Trim();

            // 7. Capitalization.
            if (english)
            {
                text = LonelyI.Replace(text, "I");
                text = CapitalizeSentences(text);
            }

            // 8. Terminal punctuation for sentence-like text at Medium+.
            if (level >= CleanupLevel.Medium && english && LooksLikeSentence(text))
                text += ".";
        }

        result.Text = text;
        return result;
    }

    /// <summary>Builds an initial prompt for Whisper that biases it toward the user's vocabulary.</summary>
    public static string? BuildWhisperPrompt(IReadOnlyList<DictionaryEntry> dictionary)
    {
        if (dictionary.Count == 0) return null;
        var words = dictionary
            .OrderByDescending(d => d.Starred)
            .ThenByDescending(d => d.Uses)
            .Take(24)
            .Select(d => d.Phrase);
        return "Glossary: " + string.Join(", ", words) + ".";
    }

    private static string ApplyDictionary(string text, IReadOnlyList<DictionaryEntry> dictionary, List<long>? matched)
    {
        foreach (var entry in dictionary)
        {
            if (string.IsNullOrWhiteSpace(entry.Phrase)) continue;
            var hit = false;

            if (!string.IsNullOrWhiteSpace(entry.Misheard))
            {
                var rx = CachedUserRegex($@"\b{Regex.Escape(entry.Misheard.Trim())}\b");
                if (rx.IsMatch(text))
                {
                    text = rx.Replace(text, entry.Phrase);
                    hit = true;
                }
            }

            // Normalize casing of the phrase itself ("wispr" → "Wispr"). Only for phrases
            // containing an uppercase letter, so plain words don't fight sentence casing.
            if (entry.Phrase.Any(char.IsUpper))
            {
                var rx = CachedUserRegex($@"\b{Regex.Escape(entry.Phrase)}\b");
                var replaced = rx.Replace(text, entry.Phrase);
                if (!string.Equals(replaced, text, StringComparison.Ordinal))
                {
                    text = replaced;
                    hit = true;
                }
            }

            if (hit && matched != null && !matched.Contains(entry.Id)) matched.Add(entry.Id);
        }
        return text;
    }

    private static Snippet? MatchWholeSnippet(string text, IReadOnlyList<Snippet> snippets)
    {
        var bare = text.TrimEnd('.', '!', '?', ',', ' ');
        return snippets.FirstOrDefault(s =>
            !string.IsNullOrWhiteSpace(s.Trigger) &&
            string.Equals(bare, s.Trigger.Trim(), StringComparison.OrdinalIgnoreCase));
    }

    private static string ApplyInlineSnippets(string text, IReadOnlyList<Snippet> snippets, List<long>? matched)
    {
        foreach (var snip in snippets)
        {
            if (string.IsNullOrWhiteSpace(snip.Trigger)) continue;
            var rx = CachedUserRegex($@"\b{Regex.Escape(snip.Trigger.Trim())}\b[,.]?");
            if (rx.IsMatch(text))
            {
                text = rx.Replace(text, snip.Content);
                if (matched != null && !matched.Contains(snip.Id)) matched.Add(snip.Id);
            }
        }
        return text;
    }

    private static string CapitalizeSentences(string text)
    {
        return Regex.Replace(text, @"(^|[.!?]\s+|\n)(\p{Ll})", m =>
            m.Groups[1].Value + char.ToUpperInvariant(m.Groups[2].Value[0]));
    }

    private static bool LooksLikeSentence(string text)
    {
        if (text.Length < 4) return false;
        var last = text[^1];
        if (!char.IsLetterOrDigit(last)) return false;
        // Single tokens (URLs, emails, code) are left alone.
        if (!text.Contains(' ')) return false;
        var lastWord = text[(text.LastIndexOf(' ') + 1)..];
        if (lastWord.Contains("://") || lastWord.Contains('@')) return false;
        return true;
    }
}
