package com.pi.androidmonitor;

import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DataListAdapter extends RecyclerView.Adapter<DataListAdapter.ViewHolder> {
    private List<String> data = new ArrayList<>();
    private int defaultColor;
    private OnItemClickListener listener;

    public interface OnItemClickListener {
        void onItemClick(String text);
    }

    public DataListAdapter(int defaultColor) {
        this.defaultColor = defaultColor;
    }

    public void setOnItemClickListener(OnItemClickListener listener) {
        this.listener = listener;
    }

    public void setData(String[] newData) {
        this.data = new ArrayList<>(Arrays.asList(newData));
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_1, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String rawText = data.get(position);
        holder.textView.setText(parseAnsi(rawText));
        holder.textView.setTypeface(android.graphics.Typeface.MONOSPACE);
        holder.textView.setTextSize(10.0f);
        holder.textView.setLineSpacing(0f, 1.0f);
        holder.textView.setPadding(6, 1, 6, 1);
        holder.textView.setMinimumHeight(0);
        holder.textView.setIncludeFontPadding(false);

        if (listener != null) {
            holder.itemView.setOnClickListener(v -> listener.onItemClick(rawText));
        }
    }

    private CharSequence parseAnsi(String input) {
        if (input == null) return "";
        String stripped = input.replaceAll("\u001B\\[[0-9;]*[A-L|N-Z|a-l|n-z]", "");
        Pattern pattern = Pattern.compile("\u001B\\[([0-9;]*)m");
        Matcher matcher = pattern.matcher(stripped);
        StringBuffer sb = new StringBuffer();
        List<ColorRange> ranges = new ArrayList<>();
        int currentColor = defaultColor;
        while (matcher.find()) {
            String codes = matcher.group(1);
            matcher.appendReplacement(sb, "");
            int start = sb.length();
            if (codes != null && !codes.isEmpty() && !codes.equals("0")) {
                for (String code : codes.split(";")) {
                    switch (code) {
                        case "31": currentColor = Color.parseColor("#FF5555"); break;
                        case "32": currentColor = Color.parseColor("#50FA7B"); break;
                        case "33": currentColor = Color.parseColor("#F1FA8C"); break;
                        case "34": currentColor = Color.parseColor("#BD93F9"); break;
                        case "36": currentColor = Color.parseColor("#8BE9FD"); break;
                        case "37": currentColor = Color.parseColor("#F8F8F2"); break;
                        case "90": currentColor = Color.parseColor("#6272A4"); break;
                    }
                }
            } else { currentColor = defaultColor; }
            ranges.add(new ColorRange(start, currentColor));
        }
        matcher.appendTail(sb);
        String cleanText = sb.toString().trim();
        if (cleanText.isEmpty()) return "";
        SpannableString spannable = new SpannableString(cleanText);
        for (int i = 0; i < ranges.size(); i++) {
            int start = ranges.get(i).start;
            int end = (i + 1 < ranges.size()) ? ranges.get(i + 1).start : spannable.length();
            if (start >= 0 && start < end && end <= spannable.length()) {
                spannable.setSpan(new ForegroundColorSpan(ranges.get(i).color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (ranges.isEmpty()) {
            int col = defaultColor;
            if (cleanText.contains("[ERROR]")) col = Color.parseColor("#FF5555");
            else if (cleanText.contains("[WARN]")) col = Color.parseColor("#F1FA8C");
            else if (cleanText.contains("[ACTION]")) col = Color.parseColor("#FF8C00");
            spannable.setSpan(new ForegroundColorSpan(col), 0, spannable.length(), 0);
        }
        return spannable;
    }

    private static class ColorRange {
        int start; int color;
        ColorRange(int s, int c) { start = s; color = c; }
    }

    @Override
    public int getItemCount() { return data.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textView;
        ViewHolder(View itemView) {
            super(itemView);
            textView = itemView.findViewById(android.R.id.text1);
        }
    }
}
