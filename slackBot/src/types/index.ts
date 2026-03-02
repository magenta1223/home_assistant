import type { KnownBlock } from '@slack/types';

export type ResponseType = 'ephemeral' | 'in_channel';

export interface SlackResponse {
    text?: string;
    blocks?: KnownBlock[];
    response_type: ResponseType;
}

export interface ParsedShared {
    isShared: boolean;
    content: string;
}

export interface ParsedGroceryItem {
    name: string;
    qty: number;
    unit: string;
}

// DB Row interfaces
export interface TodoRow {
    id: number;
    user_id: string;
    is_shared: number;
    content: string;
    is_done: number;
    due_date: string | null;
    done_at: string | null;
    created_at: string;
}

export interface MemoRow {
    id: number;
    user_id: string;
    is_shared: number;
    title: string;
    content: string;
    tags: string | null;
    created_at: string;
}

export interface ScheduleRow {
    id: number;
    user_id: string;
    is_shared: number;
    title: string;
    event_date: string;
    end_date: string | null;
    created_at: string;
}

export interface HomeStatusRow {
    id: number;
    device_name: string;
    status: string;
    set_by: string;
    updated_at: string;
}

export interface ItemLocationRow {
    id: number;
    item_name: string;
    location: string;
    set_by: string;
    updated_at: string;
}

export interface AssetRow {
    id: number;
    user_id: string;
    category: string;
    amount: number;
    note: string | null;
    recorded_at: string;
}

export interface RecipeRow {
    id: number;
    user_id: string;
    name: string;
    ingredients: string;
    steps: string;
    tags: string | null;
    servings: number | null;
    created_at: string;
}

export interface GroceryItemRow {
    id: number;
    name: string;
    unit: string;
}

export interface GroceryPurchaseRow {
    id: number;
    item_id: number;
    qty: number;
    purchased_at: string;
}

export interface ConversationMessage {
    role: 'user' | 'assistant';
    content: string;
}

export interface ChatResponse {
    type: 'question' | 'result' | 'unknown';
    text: string;
    command?: string;
    params?: string;
}

// ── Context pipeline types ──────────────────────────────────────

export type RetrievalType = 'recent' | 'similar' | 'query';

export interface FilterParams {
    keyword?: string;
    dateFrom?: string;
    dateTo?: string;
    category?: string;
    isShared?: boolean;
}

export interface ContextSpec {
    db: 'memos' | 'todos' | 'schedules' | 'home_status'
      | 'item_locations' | 'assets' | 'recipes' | 'grocery_items';
    type: RetrievalType;
    searchText?: string;   // type === 'similar' 일 때
    filter?: FilterParams; // type === 'query' 일 때
}

export interface IntentAnalysis {
    intent: string;
    contexts: ContextSpec[];
}

export interface ContextResult {
    db: string;
    type: RetrievalType;
    rows: Record<string, unknown>[];
}
