package com.chatai.an.presentation.search;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.chatai.an.app.ChatAiApplication;
import com.chatai.an.databinding.ActivityConversationSearchBinding;
import com.chatai.an.domain.model.ThemePalette;
import com.chatai.an.presentation.common.AppViewModelFactory;
import com.chatai.an.theme.GradientDrawableFactory;
import com.chatai.an.theme.ThemeApplier;
import com.chatai.an.theme.ThemeManager;

public class ConversationSearchActivity extends AppCompatActivity {

    public static final String EXTRA_CONVERSATION_ID = "extra_conversation_id";

    private ActivityConversationSearchBinding binding;
    private ConversationSearchViewModel viewModel;
    private SearchResultAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        binding = ActivityConversationSearchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        ViewCompat.setOnApplyWindowInsetsListener(binding.searchRoot, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom);
            return insets;
        });

        ChatAiApplication app = (ChatAiApplication) getApplication();
        viewModel = new ViewModelProvider(this, new AppViewModelFactory(app.getAppContainer()))
                .get(ConversationSearchViewModel.class);

        adapter = new SearchResultAdapter(conversation -> {
            Intent result = new Intent();
            result.putExtra(EXTRA_CONVERSATION_ID, conversation.getId());
            setResult(RESULT_OK, result);
            finish();
        });

        binding.rvSearchResults.setLayoutManager(new LinearLayoutManager(this));
        binding.rvSearchResults.setAdapter(adapter);

        binding.btnSearchBack.setOnClickListener(v -> finish());
        binding.btnSearchClear.setOnClickListener(v -> binding.etSearchQuery.setText(""));

        binding.etSearchQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String q = s != null ? s.toString() : "";
                binding.btnSearchClear.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                viewModel.setQuery(q);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        viewModel.getSearchResults().observe(this, list -> {
            adapter.submitList(list);
            boolean empty = list == null || list.isEmpty();
            binding.tvSearchEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
            binding.rvSearchResults.setVisibility(empty ? View.GONE : View.VISIBLE);
        });

        viewModel.getActiveTheme().observe(this, themeConfig -> {
            boolean isDark = ThemeManager.isDarkActive(this, themeConfig);
            ThemePalette palette = ThemeManager.resolvePalette(this, themeConfig);
            applyPalette(palette, isDark);
        });

        binding.etSearchQuery.requestFocus();
    }

    private void applyPalette(ThemePalette palette, boolean isDark) {
        ThemeApplier.applySystemBars(this, palette, isDark);
        binding.searchRoot.setBackgroundColor(palette.getBackgroundColor());
        binding.searchTopBar.setBackgroundColor(palette.getBackgroundColor());
        binding.btnSearchBack.setColorFilter(palette.getTextPrimaryColor());
        binding.btnSearchClear.setColorFilter(palette.getTextSecondaryColor());
        binding.ivSearchIcon.setColorFilter(palette.getPrimaryColor());
        binding.etSearchQuery.setTextColor(palette.getTextPrimaryColor());
        binding.etSearchQuery.setHintTextColor(palette.getTextSecondaryColor());
        binding.tvSearchEmpty.setTextColor(palette.getTextSecondaryColor());

        binding.searchInputBox.setBackground(GradientDrawableFactory.createPill(
                palette.getSurfaceColor(),
                palette.getBorderColor(),
                ThemeApplier.dpToPxInt(this, 1f)
        ));
        adapter.setPalette(palette);
    }
}
