using VoiceFlow.Core.Models;
using VoiceFlow.Core.Services;
using Xunit;

namespace VoiceFlow.Tests;

public class TextFormatterTests
{
    [Fact]
    public void EmptyInput_ReturnsEmpty()
    {
        var r = TextFormatter.Format("", CleanupLevel.High);
        Assert.Equal("", r.Text);
        Assert.False(r.SendEnter);
    }

    [Fact]
    public void None_LeavesTextUntouched()
    {
        var r = TextFormatter.Format("um, hello there  world", CleanupLevel.None);
        Assert.Equal("um, hello there  world", r.Text);
    }

    [Fact]
    public void Light_RemovesFillers_AndCapitalizes()
    {
        var r = TextFormatter.Format("um, hello there. uh, how are you", CleanupLevel.Light);
        Assert.Equal("Hello there. How are you", r.Text);
    }

    [Fact]
    public void Light_DoesNotAddTerminalPeriod()
    {
        var r = TextFormatter.Format("hello there", CleanupLevel.Light);
        Assert.Equal("Hello there", r.Text);
    }

    [Fact]
    public void Medium_AddsTerminalPeriod()
    {
        var r = TextFormatter.Format("hello there", CleanupLevel.Medium);
        Assert.Equal("Hello there.", r.Text);
    }

    [Fact]
    public void Medium_RemovesParentheticalFillers()
    {
        var r = TextFormatter.Format("it's, you know, totally fine", CleanupLevel.Medium);
        Assert.Equal("It's, totally fine.", r.Text);
    }

    [Fact]
    public void Medium_CollapsesRepeatedWords()
    {
        var r = TextFormatter.Format("send the the report", CleanupLevel.Medium);
        Assert.Equal("Send the report.", r.Text);
    }

    [Fact]
    public void Medium_RemovesLeadIn()
    {
        var r = TextFormatter.Format("so, let's start with the numbers", CleanupLevel.Medium);
        Assert.Equal("Let's start with the numbers.", r.Text);
    }

    [Fact]
    public void High_RemovesHedges()
    {
        var r = TextFormatter.Format("this is basically the final version", CleanupLevel.High);
        Assert.Equal("This is the final version.", r.Text);
    }

    [Fact]
    public void CapitalizesLonelyI()
    {
        var r = TextFormatter.Format("i think i can do it", CleanupLevel.Light);
        Assert.Equal("I think I can do it", r.Text);
    }

    [Fact]
    public void PressEnter_IsStrippedAndFlagged()
    {
        var r = TextFormatter.Format("sounds good, see you then press enter", CleanupLevel.Medium);
        Assert.True(r.SendEnter);
        Assert.Equal("Sounds good, see you then.", r.Text);
    }

    [Fact]
    public void PressEnter_WithAndPrefix()
    {
        var r = TextFormatter.Format("ship it and press enter", CleanupLevel.Light);
        Assert.True(r.SendEnter);
        Assert.Equal("Ship it", r.Text);
    }

    [Fact]
    public void PressEnter_AloneIsNotACommand()
    {
        var r = TextFormatter.Format("press enter", CleanupLevel.Medium);
        Assert.False(r.SendEnter);
        Assert.Equal("Press enter.", r.Text);
    }

    [Fact]
    public void NewLineAndParagraph_BecomeBreaks()
    {
        var r = TextFormatter.Format("first item new line second item new paragraph closing thought", CleanupLevel.Light);
        Assert.Equal("First item\nSecond item\n\nClosing thought", r.Text);
    }

    [Fact]
    public void Dictionary_FixesMisheardWord()
    {
        var dict = new List<DictionaryEntry>
        {
            new() { Id = 1, Phrase = "Wispr", Misheard = "whisper" },
        };
        var matched = new List<long>();
        var r = TextFormatter.Format("i love whisper flow", CleanupLevel.Light, dict,
            matchedDictionaryIds: matched);
        Assert.Equal("I love Wispr flow", r.Text);
        Assert.Contains(1L, matched);
    }

    [Fact]
    public void Dictionary_NormalizesCasing()
    {
        var dict = new List<DictionaryEntry> { new() { Id = 2, Phrase = "PostgreSQL" } };
        var r = TextFormatter.Format("we deployed postgresql yesterday", CleanupLevel.Light, dict);
        Assert.Equal("We deployed PostgreSQL yesterday", r.Text);
    }

    [Fact]
    public void Snippet_WholeUtterance_ReturnsContentVerbatim()
    {
        var snips = new List<Snippet>
        {
            new() { Id = 5, Trigger = "insert my email", Content = "chao@example.com" },
        };
        var matched = new List<long>();
        var r = TextFormatter.Format("Insert my email.", CleanupLevel.High, snippets: snips,
            matchedSnippetIds: matched);
        Assert.Equal("chao@example.com", r.Text); // no capitalization, no added period
        Assert.Contains(5L, matched);
    }

    [Fact]
    public void Snippet_WholeUtterance_WithPressEnter()
    {
        var snips = new List<Snippet>
        {
            new() { Id = 6, Trigger = "send my calendly", Content = "https://calendly.com/chao" },
        };
        var r = TextFormatter.Format("send my calendly press enter", CleanupLevel.Medium, snippets: snips);
        Assert.True(r.SendEnter);
        Assert.Equal("https://calendly.com/chao", r.Text);
    }

    [Fact]
    public void Snippet_Inline_Expands()
    {
        var snips = new List<Snippet>
        {
            new() { Id = 7, Trigger = "my address", Content = "742 Evergreen Terrace" },
        };
        var r = TextFormatter.Format("ship it to my address please", CleanupLevel.Light, snippets: snips);
        Assert.Equal("Ship it to 742 Evergreen Terrace please", r.Text);
    }

    [Fact]
    public void UrlsSurvive_MediumCleanup()
    {
        var r = TextFormatter.Format("check the docs at docs.wisprflow.ai for details", CleanupLevel.Medium);
        Assert.Contains("docs.wisprflow.ai", r.Text);
    }

    [Fact]
    public void NonEnglish_SkipsEnglishRules()
    {
        var r = TextFormatter.Format("hola, como estas", CleanupLevel.Medium, language: "es");
        Assert.DoesNotContain("I", r.Text); // no lonely-i rule
        Assert.Equal("hola, como estas", r.Text);
    }

    [Fact]
    public void PunctuationSpacing_IsRepaired()
    {
        var r = TextFormatter.Format("well ,that was fast!!She said", CleanupLevel.Light);
        Assert.Equal("Well, that was fast! She said", r.Text);
    }

    [Fact]
    public void WhisperPrompt_PrioritizesStarredWords()
    {
        var dict = new List<DictionaryEntry>
        {
            new() { Phrase = "common", Uses = 100 },
            new() { Phrase = "starred", Starred = true },
        };
        var prompt = TextFormatter.BuildWhisperPrompt(dict)!;
        Assert.StartsWith("Glossary: starred", prompt);
    }

    [Fact]
    public void WhisperArtifacts_AreCleaned()
    {
        var cleaned = TranscriptionService.CleanWhisperArtifacts("[BLANK_AUDIO] hello (coughs) world ♪");
        Assert.Equal("hello world", cleaned);
    }
}
