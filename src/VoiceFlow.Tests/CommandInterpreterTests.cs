using VoiceFlow.Core.Services;
using Xunit;

namespace VoiceFlow.Tests;

public class CommandInterpreterTests
{
    private readonly CommandInterpreter _interpreter = new();

    [Theory]
    [InlineData("make this all caps", "hello world", "HELLO WORLD")]
    [InlineData("uppercase this please", "hello", "HELLO")]
    [InlineData("lowercase", "HELLO World", "hello world")]
    public async Task LocalCaseTransforms(string instruction, string input, string expected)
    {
        var result = await _interpreter.ExecuteAsync(instruction, input, llm: null);
        Assert.True(result.Success);
        Assert.Equal(expected, result.Output);
        Assert.False(result.UsedLlm);
    }

    [Fact]
    public async Task BulletList_SplitsSentences()
    {
        var result = await _interpreter.ExecuteAsync(
            "make this a bullet list", "First point. Second point. Third point.", llm: null);
        Assert.True(result.Success);
        Assert.Equal("- First point\n- Second point\n- Third point", result.Output);
    }

    [Fact]
    public async Task NumberedList_Numbers()
    {
        var result = await _interpreter.ExecuteAsync(
            "turn this into a numbered list", "Alpha. Beta.", llm: null);
        Assert.Equal("1. Alpha\n2. Beta", result.Output);
    }

    [Fact]
    public async Task RemoveLineBreaks_Joins()
    {
        var result = await _interpreter.ExecuteAsync(
            "remove the line breaks", "one\ntwo\n\nthree", llm: null);
        Assert.Equal("one two three", result.Output);
    }

    [Fact]
    public async Task Polish_RunsLocalHighCleanup()
    {
        var result = await _interpreter.ExecuteAsync(
            "polish this", "um, basically the numbers look good", llm: null);
        Assert.True(result.Success);
        Assert.Equal("The numbers look good.", result.Output);
    }

    [Fact]
    public async Task FreeformWithoutLlm_ReturnsFriendlyError()
    {
        var result = await _interpreter.ExecuteAsync(
            "translate this to Polish", "good morning", llm: null);
        Assert.False(result.Success);
        Assert.Contains("API key", result.Error);
    }

    [Fact]
    public async Task FreeformWithLlm_UsesDelegate()
    {
        var result = await _interpreter.ExecuteAsync(
            "translate this to Polish", "good morning",
            (_, _, _) => Task.FromResult("dzień dobry"));
        Assert.True(result.Success);
        Assert.True(result.UsedLlm);
        Assert.Equal("dzień dobry", result.Output);
    }

    [Fact]
    public async Task GenerationWithoutSelection_NeedsLlm()
    {
        var result = await _interpreter.ExecuteAsync(
            "write a haiku about rain", "", llm: null);
        Assert.False(result.Success);
    }

    [Fact]
    public async Task EmptyInstruction_Errors()
    {
        var result = await _interpreter.ExecuteAsync("", "text", llm: null);
        Assert.False(result.Success);
    }
}
