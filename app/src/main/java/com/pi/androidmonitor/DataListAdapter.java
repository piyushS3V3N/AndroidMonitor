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
                .inflate(R.layout.item_log, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String rawText = data.get(position);
        CharSequence styledText = parseAnsi(rawText);
        holder.textView.setText(styledText);
        
        // 1. Zebra Striping for better tracking
        if (position % 2 == 0) {
            holder.container.setBackgroundColor(Color.parseColor("#0D0D0F")); // Slate Night
        } else {
            holder.container.setBackgroundColor(Color.parseColor("#050505")); // True Black
        }

        // 2. Dynamic Indicator Coloring
        int indicatorColor = defaultColor;
        String cleanText = styledText.toString();
        if (cleanText.contains("[ERROR]")) indicatorColor = Color.parseColor("#FF5555");
        else if (cleanText.contains("[WARN]")) indicatorColor = Color.parseColor("#F1FA8C");
        else if (cleanText.contains("[INFO]")) indicatorColor = Color.parseColor("#8BE9FD");
        else if (cleanText.contains("[ACTION]")) indicatorColor = Color.parseColor("#FF8C00");
        
        holder.indicator.setBackgroundColor(indicatorColor);

        if (listener != null) {
            holder.itemView.setOnClickListener(v -> listener.onItemClick(rawText));
        }
    }

    private CharSequence parseAnsi(String input) {
        if (input == null) return "";
        
        // Brighter "Neon" ANSI palette for dark background
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
                        case "31": currentColor = Color.parseColor("#FF6E67"); break; // Neon Red
                        case "32": currentColor = Color.parseColor("#5AF78E"); break; // Neon Green
                        case "33": currentColor = Color.parseColor("#F3F99D"); break; // Neon Yellow
                        case "34": currentColor = Color.parseColor("#CAA9FA"); break; // Neon Purple
                        case "36": currentColor = Color.parseColor("#9AEDFE"); break; // Neon Cyan
                        case "37": currentColor = Color.parseColor("#F1F1F0"); break; // Off White
                        case "90": currentColor = Color.parseColor("#6272A4"); break; // Dim Gray
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
            if (cleanText.contains("[ERROR]")) col = Color.parseColor("#FF6E67");
            else if (cleanText.contains("[WARN]")) col = Color.parseColor("#F3F99D");
            else if (cleanText.contains("[INFO]")) col = Color.parseColor("#9AEDFE");
            else if (cleanText.contains("[ACTION]")) col = Color.parseColor("#FFB86C");
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
        View indicator;
        View container;
        ViewHolder(View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.log_text);
            indicator = itemView.findViewById(R.id.log_indicator);
            container = itemView.findViewById(R.id.log_item_container);
        }
    }
}
