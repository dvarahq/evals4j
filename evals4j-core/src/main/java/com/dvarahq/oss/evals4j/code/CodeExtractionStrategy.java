package com.dvarahq.oss.evals4j.code;

/** How to get source code out of a model's response before checking it. */
public enum CodeExtractionStrategy {

    /** The output already is code. */
    NONE,

    /** Take the fenced markdown code blocks, skipping shell and JSON blocks. */
    MARKDOWN_CODE_BLOCKS,

    /** Ask a model to pull the code out. Costs a call, but handles responses no regex would. */
    LLM
}
