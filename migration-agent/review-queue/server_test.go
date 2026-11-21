package main

import (
	"encoding/json"
	"net/http/httptest"
	"testing"
)

func testItems() []QueueItem {
	return []QueueItem{
		{ID: 1, Kind: "CONNECT_BY_REFUSE", RefusalReason: "CONNECT BY hierarchical query", Disposition: DispositionNotAttempted},
		{ID: 2, Kind: "TOCHAR_FORMAT_REFUSE", RefusalReason: "legacy TO_CHAR date format mask", Disposition: DispositionProven},
	}
}

func TestHandleQueueReturnsAllItems(t *testing.T) {
	srv := NewServer(testItems())
	ts := httptest.NewServer(srv.Mux())
	defer ts.Close()

	resp, err := ts.Client().Get(ts.URL + "/queue")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	var items []QueueItem
	if err := json.NewDecoder(resp.Body).Decode(&items); err != nil {
		t.Fatal(err)
	}
	if len(items) != 2 {
		t.Fatalf("expected 2 items, got %d", len(items))
	}
}

func TestHandleQueueItemReturnsOne(t *testing.T) {
	srv := NewServer(testItems())
	ts := httptest.NewServer(srv.Mux())
	defer ts.Close()

	resp, err := ts.Client().Get(ts.URL + "/queue/2")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	var item QueueItem
	if err := json.NewDecoder(resp.Body).Decode(&item); err != nil {
		t.Fatal(err)
	}
	if item.ID != 2 || item.Disposition != DispositionProven {
		t.Fatalf("unexpected item: %+v", item)
	}
}

func TestHandleQueueItemNotFound(t *testing.T) {
	srv := NewServer(testItems())
	ts := httptest.NewServer(srv.Mux())
	defer ts.Close()

	resp, err := ts.Client().Get(ts.URL + "/queue/999")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 404 {
		t.Fatalf("expected 404, got %d", resp.StatusCode)
	}
}

func TestHandleStats(t *testing.T) {
	srv := NewServer(testItems())
	ts := httptest.NewServer(srv.Mux())
	defer ts.Close()

	resp, err := ts.Client().Get(ts.URL + "/stats")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()

	var stats Stats
	if err := json.NewDecoder(resp.Body).Decode(&stats); err != nil {
		t.Fatal(err)
	}
	if stats.TotalRefused != 2 || stats.ProvenEquivalent != 1 || stats.NotAttempted != 1 {
		t.Fatalf("unexpected stats: %+v", stats)
	}
}
