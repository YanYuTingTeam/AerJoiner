package com.aermini.gui;

public class PageState {
    public final int page;
    public final int maxPage;
    public final String categoryName;

    public PageState(int page, int maxPage, String categoryName) {
        this.page = page;
        this.maxPage = maxPage;
        this.categoryName = categoryName;
    }
}