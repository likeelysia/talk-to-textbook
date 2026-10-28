import importlib.util, pathlib, unittest

spec=importlib.util.spec_from_file_location('chunks',pathlib.Path(__file__).with_name('build_book_chunks.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


class RawChunkTests(unittest.TestCase):
    def test_overlap_never_skips_source_characters(self):
        text=''.join(chr(0x4e00+i) for i in range(1700))
        pieces=m.split_chunks(text)
        self.assertTrue(all(len(p)<=600 for p in pieces))
        combined=pieces[0]
        for p in pieces[1:]:
            self.assertEqual(combined[-90:],p[:90]);combined+=p[90:]
        self.assertEqual(text,combined)

    def test_id_retains_same_title_on_other_pages_and_books(self):
        book={'source':'例书.pdf','sha256':'a'*64}; text='相同标题。不同段落也需要保留原文。'
        first=m.item_of(book,20,1,text)
        self.assertNotEqual(first['id'],m.item_of(book,21,1,text)['id'])
        self.assertNotEqual(first['id'],m.item_of(book|{'sha256':'b'*64},20,1,text)['id'])
        self.assertEqual(first,m.item_of(book,20,1,text));self.assertEqual(first['location'],'第 20 页')

    def test_cleanup_preserves_english_and_body_numbers(self):
        self.assertEqual(m.clean('页眉\n认 知失 调 cognitive dissonance\n2018 年的研究\n123\n夸克扫描王',['页眉']),
                         '认知失调 cognitive dissonance\n2018 年的研究')

    def test_bad_chunk_configuration_rejected(self):
        with self.assertRaises(ValueError):m.split_chunks('abc',100,100)

    def test_ellipses_in_body_are_not_a_table_of_contents(self):
        self.assertFalse(m.is_contents('\n'.join(['思想……仍然可以继续……并非目录。']*8)))
        self.assertTrue(m.is_contents('\n'.join(['章节名称…………12']*6)))


if __name__=='__main__':unittest.main()
