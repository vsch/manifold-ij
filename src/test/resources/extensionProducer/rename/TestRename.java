package extensionProducer.rename;

import extensionProducer.sample.Sample;

public class TestRename
{
  public void foo()
  {
    Short sh = null;
    sh.<caret>favoriteSong();
    sh.favoriteFood();

    Long lo = null;
    lo.favoriteSong();
  }
}