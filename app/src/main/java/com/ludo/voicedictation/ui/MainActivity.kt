package com.ludo.voicedictation.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import com.ludo.voicedictation.R
import com.ludo.voicedictation.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        setupViewPager()
    }

    private fun setupViewPager() {
        val adapter = MainPagerAdapter(this)
        binding.viewPager.adapter = adapter
        binding.viewPager.offscreenPageLimit = 2

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            when (position) {
                0 -> {
                    tab.text = getString(R.string.tab_settings)
                    tab.setIcon(R.drawable.ic_settings)
                }
                1 -> {
                    tab.text = getString(R.string.tab_vocabulary)
                    tab.setIcon(R.drawable.ic_vocabulary)
                }
                2 -> {
                    tab.text = getString(R.string.tab_history)
                    tab.setIcon(R.drawable.ic_history)
                }
            }
        }.attach()
    }

    private class MainPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> SettingsFragment()
                1 -> VocabularyFragment()
                2 -> HistoryFragment()
                else -> SettingsFragment()
            }
        }
    }
}
